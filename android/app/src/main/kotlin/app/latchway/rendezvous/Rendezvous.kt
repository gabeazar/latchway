// The rendezvous client (PROTOCOL.md §3) on OkHttp WebSockets: one socket
// per host registration or joiner session, JSON text frames, a keepalive
// the server answers without waking.
package app.latchway.rendezvous

import app.latchway.core.B64
import app.latchway.core.Msg
import app.latchway.core.Protocol
import app.latchway.core.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** An error the rendezvous reported, or a refused upgrade mapped to a code. */
class RendezvousException(val code: String, message: String = "rendezvous: $code") : Exception(message)

/** One item from the socket: a message, or the failure that ended it. */
sealed class Inbound {
    data class Message(val msg: Msg) : Inbound()
    data class Failed(val error: Throwable) : Inbound()
}

object RendezvousUrls {
    /**
     * Normalises "latchway.app", "https://latchway.app/" or
     * "http://10.0.2.2:8787" into a base URL. Returns null when unusable.
     */
    fun base(raw: String): HttpUrl? {
        var s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        val u = s.toHttpUrlOrNull() ?: return null
        if (u.scheme != "https" && u.scheme != "http") return null
        return u.newBuilder().query(null).fragment(null).build()
    }

    /** The host string that goes into links: "latchway.app" or "host:port". */
    fun linkHost(base: HttpUrl): String =
        if (base.port == HttpUrl.defaultPort(base.scheme)) base.host else "${base.host}:${base.port}"
}

class RendezvousConnection internal constructor(
    private val ws: WebSocket,
    val inbound: Channel<Inbound>,
    private val scope: CoroutineScope,
) {
    fun send(msg: Msg): Boolean = ws.send(msg.encode())

    fun close() {
        scope.cancel()
        ws.close(1000, "bye")
    }
}

object Rendezvous {
    private const val KEEPALIVE_MS = 25_000L
    private const val DIAL_TIMEOUT_MS = 20_000L

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WebSockets are long-lived
            .pingInterval(0, TimeUnit.SECONDS) // the protocol has its own keepalive
            .build()
    }

    /** Registers a share (§3.1) and returns once the server says ready. */
    suspend fun dialHost(base: HttpUrl, shareId: String, regKey: ByteArray, token: String?): RendezvousConnection {
        require(regKey.size == Protocol.REG_KEY_LEN)
        val url = base.newBuilder().addPathSegments("v1/host").addQueryParameter("share", shareId).build()
        val conn = dial(url, token)
        try {
            conn.send(Msg(t = Wire.T_REGISTER, reg = B64.encode(regKey)))
            val first = withTimeout(DIAL_TIMEOUT_MS) { conn.next() }
            if (first.t != Wire.T_READY) throw RendezvousException(Wire.ERR_PROTOCOL, "unexpected first message ${first.t}")
            return conn
        } catch (e: Throwable) {
            conn.close()
            throw e
        }
    }

    /**
     * Joins a share (§3.2) and returns the connection plus the joined
     * message. A refused upgrade surfaces as not_found or busy.
     */
    suspend fun dialJoiner(base: HttpUrl, shareId: String): Pair<RendezvousConnection, Msg> {
        val url = base.newBuilder().addPathSegments("v1/join/$shareId").build()
        val conn = dial(url, null)
        try {
            val first = withTimeout(DIAL_TIMEOUT_MS) { conn.next() }
            if (first.t != Wire.T_JOINED) throw RendezvousException(Wire.ERR_PROTOCOL, "unexpected first message ${first.t}")
            return conn to first
        } catch (e: Throwable) {
            conn.close()
            throw e
        }
    }

    /** Reads the next message, raising rendezvous errors as exceptions. */
    suspend fun RendezvousConnection.next(): Msg {
        when (val item = inbound.receive()) {
            is Inbound.Message -> return item.msg
            is Inbound.Failed -> throw item.error
        }
    }

    private suspend fun dial(url: HttpUrl, token: String?): RendezvousConnection {
        val wsUrl = url.newBuilder().scheme(if (url.scheme == "https") "https" else "http").build()
        val req = Request.Builder().url(wsUrl).apply {
            if (!token.isNullOrEmpty()) header("Authorization", "Bearer $token")
        }.build()
        val inbound = Channel<Inbound>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ws = suspendCancellableCoroutine<WebSocket> { cont ->
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (cont.isActive) cont.resume(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val m = Msg.decode(text) ?: run {
                        inbound.trySend(Inbound.Failed(RendezvousException(Wire.ERR_PROTOCOL, "malformed message")))
                        return
                    }
                    when (m.t) {
                        Wire.T_PONG -> {}
                        Wire.T_ERROR -> inbound.trySend(Inbound.Failed(RendezvousException(m.code ?: Wire.ERR_INTERNAL)))
                        else -> inbound.trySend(Inbound.Message(m))
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    inbound.trySend(Inbound.Failed(RendezvousException(Wire.ERR_CLOSED, "connection closed ($code $reason)")))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val err: Throwable = when (response?.code) {
                        404 -> RendezvousException(Wire.ERR_NOT_FOUND)
                        429 -> RendezvousException(Wire.ERR_BUSY)
                        401, 403 -> RendezvousException(Wire.ERR_FORBIDDEN)
                        null -> t
                        else -> RendezvousException(Wire.ERR_INTERNAL, "rendezvous refused (HTTP ${response.code})")
                    }
                    if (cont.isActive) cont.resumeWithException(err) else inbound.trySend(Inbound.Failed(err))
                }
            }
            val socket = client.newWebSocket(req, listener)
            cont.invokeOnCancellation { socket.cancel() }
        }
        val conn = RendezvousConnection(ws, inbound, scope)
        scope.launch {
            while (isActive) {
                delay(KEEPALIVE_MS)
                ws.send(Msg(t = Wire.T_PING).encode())
            }
        }
        return conn
    }
}
