// The sender side of PROTOCOL.md §4, a port of internal/peer/host.go: a
// Host keeps a registration at the rendezvous and runs one session per
// joiner on libwebrtc.
package app.latchway.peer

import app.latchway.core.B64
import app.latchway.core.ChunkSealer
import app.latchway.core.KeySchedule
import app.latchway.core.Keys
import app.latchway.core.Meta
import app.latchway.core.Msg
import app.latchway.core.Opener
import app.latchway.core.Proof
import app.latchway.core.Protocol
import app.latchway.core.Sealer
import app.latchway.core.Share
import app.latchway.core.Wire
import app.latchway.core.Direction
import app.latchway.core.IceServer
import app.latchway.core.randomBytes
import app.latchway.rendezvous.Inbound
import app.latchway.rendezvous.Rendezvous
import app.latchway.rendezvous.RendezvousConnection
import app.latchway.rendezvous.RendezvousException
import app.latchway.rendezvous.RendezvousUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/** Describes the offered file. Size is -1 when unknown. */
data class FileMeta(val name: String, val size: Long, val mime: String, val from: String? = null)

/** Progress reports from a Host or a Receiver. */
sealed class Event {
    data class Registered(val error: Throwable? = null) : Event()
    data class Joined(val sid: String) : Event()
    data class AuthFailed(val sid: String) : Event()
    data class ApprovalWait(val sid: String) : Event()
    data class Denied(val sid: String, val code: String) : Event()
    data class Connecting(val sid: String) : Event()
    data class Connected(val sid: String) : Event()
    data class Progress(val sid: String, val bytes: Long, val total: Long) : Event()
    data class Done(val sid: String, val bytes: Long) : Event()
    data class Failed(val sid: String, val error: Throwable) : Event()
    data class Left(val sid: String) : Event()
    object Stopped : Event()
}

class HostOptions(
    val base: HttpUrl,
    val share: Share,
    val regKey: ByteArray,
    val token: String? = null,
    val password: String = "",
    val meta: FileMeta,
    val open: () -> InputStream,
    /** Asked before each transfer when set; false sends `denied`. */
    val approve: (suspend (sid: String) -> Boolean)? = null,
    val maxDownloads: Int = 0,
    val maxConcurrent: Int = 0,
    val expiresAt: Long = 0,
    val relayOnly: Boolean = false,
    val onEvent: (Event) -> Unit = {},
)

private const val HANDSHAKE_TIMEOUT_MS = 90_000L
private const val AUTH_TIMEOUT_MS = 60_000L
private const val OFFER_TIMEOUT_MS = 10 * 60_000L
private const val THROTTLE_AFTER = 5
private const val THROTTLE_WINDOW_MS = 10 * 60_000L
private const val THROTTLE_DELAY_MS = 10_000L
private const val SESSION_QUEUE = 64

class Host(private val opts: HostOptions) {
    private val keys: Keys = KeySchedule.derive(opts.share.id, opts.share.secret, opts.password)
    private val sessions = HashMap<String, HostSession>()
    private val lock = Any()
    private var completed = 0
    private var active = 0
    private val failures = ArrayDeque<Long>()
    private var stopped = false
    private val stopSignal = Channel<Unit>(1)

    /** Fires when the network changes so the rendezvous is re-dialled at once. */
    val wake = Channel<Unit>(Channel.CONFLATED)

    val link: String get() = opts.share.link(RendezvousUrls.linkHost(opts.base))
    val completedCount: Int get() = synchronized(lock) { completed }

    private fun event(e: Event) = opts.onEvent(e)

    /** Why a host stopped serving. */
    enum class Outcome { CANCELLED, LIMIT_REACHED, EXPIRED }

    @Volatile
    private var expired = false

    /**
     * Registers and serves until cancelled, expired, the download limit is
     * reached, or the rendezvous refuses the registration (throws
     * RendezvousException). Transfers already running over WebRTC are
     * left alone across rendezvous reconnections.
     */
    suspend fun run(): Outcome = coroutineScope {
        val sessionScope = CoroutineScope(currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]))
        var backoff = 1_000L
        var result: Throwable? = null
        try {
            val expiry = if (opts.expiresAt > 0) launch {
                val wait = opts.expiresAt - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                expired = true
                synchronized(lock) { stopped = true }
                stopSignal.trySend(Unit)
            } else null
            while (isActive) {
                if (synchronized(lock) { stopped }) break
                val conn = try {
                    Rendezvous.dialHost(opts.base, opts.share.idString, opts.regKey, opts.token)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (e is RendezvousException && (e.code == Wire.ERR_FORBIDDEN || e.code == Wire.ERR_BUSY)) {
                        result = e
                        break
                    }
                    event(Event.Registered(e))
                    if (!sleepOrWake(backoff)) break
                    backoff = minOf(backoff * 2, 30_000L)
                    continue
                }
                backoff = 1_000L
                event(Event.Registered())
                val err = try {
                    serve(conn, sessionScope)
                } finally {
                    conn.close()
                }
                if (err == null || !isActive) break
                if (err is RendezvousException && (err.code == Wire.ERR_REPLACED || err.code == Wire.ERR_FORBIDDEN)) {
                    result = err
                    break
                }
                abortSignaling()
                event(Event.Registered(err))
                if (!sleepOrWake(backoff)) break
                backoff = minOf(backoff * 2, 30_000L)
            }
            expiry?.cancel()
            // Let transfers that already have a data channel finish.
            while (isActive && synchronized(lock) { active } > 0) delay(100)
        } finally {
            sessionScope.coroutineContext[Job]?.cancel()
            event(Event.Stopped)
        }
        result?.let { throw it }
        when {
            !isActive -> Outcome.CANCELLED
            expired -> Outcome.EXPIRED
            synchronized(lock) { stopped } -> Outcome.LIMIT_REACHED
            else -> Outcome.CANCELLED
        }
    }

    private suspend fun sleepOrWake(ms: Long): Boolean {
        withTimeoutOrNull(ms) { wake.receive() }
        return currentCoroutineContext().isActive
    }

    /** Returns null when the host should stop, else the error that ended the connection. */
    private suspend fun serve(conn: RendezvousConnection, sessionScope: CoroutineScope): Throwable? {
        val send: (Msg) -> Unit = { conn.send(it) }
        while (true) {
            val item: Any = select {
                conn.inbound.onReceive { it }
                stopSignal.onReceive { it }
            }
            when (item) {
                is Unit -> return null
                is Inbound.Failed -> return item.error
                is Inbound.Message -> {
                    val m = item.msg
                    when (m.t) {
                        Wire.T_JOIN -> startSession(m, send, sessionScope)
                        Wire.T_SIG -> {
                            val s = synchronized(lock) { sessions[m.sid] }
                            if (s == null) {
                                send(Msg(t = Wire.T_LEAVE, sid = m.sid))
                            } else {
                                val pm = m.d?.takeIf { it.length <= Protocol.MAX_SIGNAL_PAYLOAD }?.let { Msg.decode(it) }
                                s.deliver(pm ?: Msg(t = Wire.T_ERROR, code = Wire.ERR_PROTOCOL))
                            }
                        }
                        Wire.T_LEAVE -> synchronized(lock) { sessions[m.sid] }?.deliver(Msg(t = Wire.T_LEAVE))
                    }
                }
            }
        }
    }

    private fun startSession(join: Msg, send: (Msg) -> Unit, scope: CoroutineScope) {
        val sid = join.sid ?: return
        val s = HostSession(this, sid, join.ice ?: emptyList(), send)
        synchronized(lock) { sessions[sid] = s }
        event(Event.Joined(sid))
        s.job = scope.launch {
            try {
                s.run()
            } finally {
                synchronized(lock) { if (sessions[sid] === s) sessions.remove(sid) }
            }
        }
    }

    private fun abortSignaling() {
        val toCancel = synchronized(lock) { sessions.values.filter { !it.transferring } }
        toCancel.forEach { it.job?.cancel() }
    }

    internal suspend fun throttle(received: Long) {
        val throttled = synchronized(lock) {
            pruneFailures(received)
            failures.size >= THROTTLE_AFTER
        }
        if (throttled) {
            val wait = THROTTLE_DELAY_MS - (System.currentTimeMillis() - received)
            if (wait > 0) delay(wait)
        }
    }

    internal fun recordFailure() {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            pruneFailures(now)
            failures.addLast(now)
        }
    }

    private fun pruneFailures(now: Long) {
        while (failures.isNotEmpty() && now - failures.first() >= THROTTLE_WINDOW_MS) failures.removeFirst()
    }

    /** Returns an error code (expired, busy) or null after reserving a slot. */
    internal fun admit(): String? = synchronized(lock) {
        if (stopped) return Wire.ERR_EXPIRED
        if (opts.maxDownloads > 0 && completed + active >= opts.maxDownloads) {
            return if (active > 0) Wire.ERR_BUSY else Wire.ERR_EXPIRED
        }
        if (opts.maxConcurrent > 0 && active >= opts.maxConcurrent) return Wire.ERR_BUSY
        active++
        null
    }

    internal fun release(done: Boolean) {
        val stop = synchronized(lock) {
            active--
            if (done) {
                completed++
                if (opts.maxDownloads > 0 && completed >= opts.maxDownloads && !stopped) {
                    stopped = true
                    true
                } else false
            } else false
        }
        if (stop) stopSignal.trySend(Unit)
    }

    internal val options get() = opts
    internal val longTermKeys get() = keys
    internal fun fire(e: Event) = event(e)
}

private object JoinerGone : Exception("joiner left")

internal class HostSession(
    private val host: Host,
    val sid: String,
    private val ice: List<IceServer>,
    private val send: (Msg) -> Unit,
) {
    private val inbox = Channel<Msg>(SESSION_QUEUE)
    var job: Job? = null

    @Volatile
    var transferring = false
        private set

    fun deliver(m: Msg) {
        if (inbox.trySend(m).isFailure) job?.cancel() // a flooding peer
    }

    private fun sig(m: Msg) = send(Msg(t = Wire.T_SIG, sid = sid, d = m.encode()))
    private fun sigRaw(d: String) = send(Msg(t = Wire.T_SIG, sid = sid, d = d))
    private fun leave() = send(Msg(t = Wire.T_LEAVE, sid = sid))

    private suspend fun next(timeoutMs: Long): Msg {
        val m = withTimeoutOrNull(timeoutMs) { inbox.receive() } ?: throw SessionException(Wire.ERR_TIMEOUT)
        if (m.t == Wire.T_LEAVE) throw JoinerGone
        return m
    }

    suspend fun run() {
        try {
            handshakeAndTransfer()
        } catch (e: CancellationException) {
            throw e
        } catch (e: JoinerGone) {
            host.fire(Event.Left(sid))
        } catch (e: Throwable) {
            host.fire(Event.Failed(sid, e))
        }
    }

    private suspend fun handshakeAndTransfer() {
        val opts = host.options
        val pw = opts.password.isNotEmpty()

        // 1. hello.
        val hostNonce = randomBytes(Protocol.NONCE_LEN)
        sig(Msg(t = Wire.T_HELLO, v = Protocol.VERSION, pw = pw, n = B64.encode(hostNonce)))

        // 2. proof.
        val auth = next(AUTH_TIMEOUT_MS)
        val received = System.currentTimeMillis()
        val joinerNonce = auth.n?.let { B64.decode(it) }
        val proof = auth.p?.let { B64.decode(it) }
        if (auth.t != Wire.T_AUTH || joinerNonce == null || joinerNonce.size != Protocol.NONCE_LEN || proof == null) {
            sig(Msg(t = Wire.T_ERROR, code = Wire.ERR_PROTOCOL))
            leave()
            throw SessionException(Wire.ERR_PROTOCOL)
        }
        host.throttle(received)
        if (!Proof.verify(host.longTermKeys.auth, Protocol.VERSION, pw, hostNonce, joinerNonce, proof)) {
            host.recordFailure()
            host.fire(Event.AuthFailed(sid))
            sig(Msg(t = Wire.T_ERROR, code = Wire.ERR_BAD_AUTH))
            leave()
            throw SessionException(Wire.ERR_BAD_AUTH)
        }

        // 3. session keys.
        val sk = KeySchedule.session(host.longTermKeys.root, hostNonce, joinerNonce)
        val sealer = Sealer(sk.sig, Direction.HOST_TO_JOINER)
        val opener = Opener(sk.sig, Direction.JOINER_TO_HOST)
        fun enc(m: Msg) = sig(Msg(t = Wire.T_ENC, c = sealer.seal(m.encodeBytes())))
        fun encErr(code: String): Nothing {
            enc(Msg(t = Wire.T_ERROR, code = code))
            leave()
            host.fire(Event.Denied(sid, code))
            throw SessionException(code)
        }
        suspend fun nextEnc(timeoutMs: Long): Msg {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val m = next(maxOf(1, deadline - System.currentTimeMillis()))
                if (m.t != Wire.T_ENC) continue
                val pt = m.c?.let { opener.open(it) } ?: throw SessionException(Wire.ERR_PROTOCOL)
                return Msg.decode(pt) ?: throw SessionException(Wire.ERR_PROTOCOL)
            }
        }

        // 4. meta, padded.
        val approval = opts.approve != null
        val padded = Meta.padded(opts.meta.name, opts.meta.size, opts.meta.mime, opts.meta.from, approval)
            ?: throw SessionException(Wire.ERR_INTERNAL, "meta does not fit")
        sigRaw(Msg(t = Wire.T_ENC, c = sealer.seal(padded)).encode())

        // 5. approval and admission.
        if (approval) {
            host.fire(Event.ApprovalWait(sid))
            if (!opts.approve!!.invoke(sid)) encErr(Wire.ERR_DENIED)
        }
        host.admit()?.let { encErr(it) }
        var completed = false
        try {
            // 6. go, with the full ICE list (TURN included).
            enc(Msg(t = Wire.T_GO, ice = ice))

            // 7. offer / answer.
            val offer = nextEnc(OFFER_TIMEOUT_MS)
            when (offer.t) {
                Wire.T_ERROR -> throw SessionException(offer.code ?: Wire.ERR_PROTOCOL)
                Wire.T_OFFER -> {}
                else -> throw SessionException(Wire.ERR_PROTOCOL)
            }
            host.fire(Event.Connecting(sid))
            val peer = Peer.create(ice, opts.relayOnly)
            try {
                peer.setRemote(SessionDescription(SessionDescription.Type.OFFER, offer.sdp ?: ""))
                val answer = peer.createAnswer()
                peer.setLocal(answer)
                enc(Msg(t = Wire.T_ANSWER, sdp = peer.pc.localDescription?.description ?: answer.description))

                // 8. trickle ICE until the channel opens, then stream.
                val sent = AtomicLong(0)
                var streaming = false
                var streamJob: kotlinx.coroutines.Deferred<Unit>? = null
                val start = System.currentTimeMillis()
                coroutineScope {
                    while (true) {
                        val streamDone = streamJob
                        val item: Any = select {
                            inbox.onReceive { it }
                            peer.events.onReceive { it }
                            if (streamDone != null) streamDone.onAwait { StreamFinished }
                            if (!streaming) {
                                onTimeout(maxOf(1, HANDSHAKE_TIMEOUT_MS - (System.currentTimeMillis() - start))) { Timeout }
                            }
                        }
                        when (item) {
                            Timeout -> throw SessionException(Wire.ERR_TIMEOUT)
                            StreamFinished -> {
                                streamJob = null // all chunks sent; wait for `done`
                            }
                            is Msg -> when (item.t) {
                                Wire.T_LEAVE -> if (!streaming) throw JoinerGone
                                Wire.T_ERROR -> if (!streaming) throw SessionException(item.code ?: Wire.ERR_PROTOCOL)
                                Wire.T_ENC -> {
                                    val pt = item.c?.let { opener.open(it) }
                                    if (pt == null) {
                                        if (!streaming) throw SessionException(Wire.ERR_PROTOCOL)
                                        continue
                                    }
                                    val pm = Msg.decode(pt) ?: continue
                                    when (pm.t) {
                                        Wire.T_ICE -> peer.addCandidate(pm)
                                        Wire.T_ICE_DONE -> {}
                                        Wire.T_ERROR -> if (!streaming) throw SessionException(pm.code ?: Wire.ERR_PROTOCOL)
                                    }
                                }
                            }
                            is PeerEvent -> when (item) {
                                is PeerEvent.Candidate -> enc(candidateMsg(item.candidate))
                                PeerEvent.GatheringDone -> enc(Msg(t = Wire.T_ICE_DONE))
                                is PeerEvent.State -> when (item.state) {
                                    PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.CLOSED ->
                                        throw SessionException(Wire.ERR_PROTOCOL, "connection ${item.state}")
                                    else -> {}
                                }
                                PeerEvent.ChannelOpen -> if (!streaming) {
                                    streaming = true
                                    transferring = true
                                    host.fire(Event.Connected(sid))
                                    val input = try {
                                        opts.open()
                                    } catch (e: Exception) {
                                        peer.sendText(Msg(t = Wire.T_ABORT, reason = "cannot open file"))
                                        throw e
                                    }
                                    streamJob = async(Dispatchers.IO) {
                                        input.use { stream(peer, ChunkSealer(sk.file), it, sent) }
                                    }
                                }
                                PeerEvent.ChannelClosed -> if (!completed) throw SessionException(Wire.ERR_PROTOCOL, "channel closed")
                                is PeerEvent.Text -> {
                                    val cm = Msg.decode(item.text) ?: continue
                                    when (cm.t) {
                                        Wire.T_PROGRESS -> cm.bytes?.let { host.fire(Event.Progress(sid, it, opts.meta.size)) }
                                        Wire.T_DONE -> {
                                            completed = true
                                            host.fire(Event.Done(sid, sent.get()))
                                            streamJob?.cancel()
                                            return@coroutineScope
                                        }
                                        Wire.T_ABORT -> throw SessionException(Wire.ERR_CLOSED, "receiver aborted: ${cm.reason}")
                                    }
                                }
                                is PeerEvent.Binary -> {}
                            }
                        }
                    }
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) { peer.close() }
            }
        } finally {
            host.release(completed)
        }
    }

    private object Timeout
    private object StreamFinished

    /** Seals the file into chunk frames with the §4.4 flow control. */
    private suspend fun stream(peer: Peer, sealer: ChunkSealer, input: InputStream, sent: AtomicLong) {
        val buf = ByteArray(Protocol.CHUNK_SIZE)
        val size = host.options.meta.size
        var total = 0L
        var index = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            var last = n < buf.size
            total += n
            if (size >= 0) {
                if (total > size) throw SessionException(Wire.ERR_INTERNAL, "file grew while sending")
                if (total == size) last = true else if (last) throw SessionException(Wire.ERR_INTERNAL, "file shrank while sending")
            }
            val frame = sealer.seal(index, last, buf, n)
            if (peer.bufferedAmount() > HIGH_WATER) {
                while (peer.bufferedAmount() > LOW_WATER) {
                    withTimeoutOrNull(100) { peer.bufferedLow.receive() }
                    currentCoroutineContext().ensureActive()
                }
            }
            if (!peer.sendBinary(frame)) throw SessionException(Wire.ERR_PROTOCOL, "send failed")
            sent.set(total)
            index++
            if (last) return
        }
    }
}
