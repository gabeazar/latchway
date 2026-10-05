// The receiver side of PROTOCOL.md §4, a port of internal/peer/joiner.go.
package app.latchway.peer

import app.latchway.core.B64
import app.latchway.core.ChunkOpener
import app.latchway.core.Direction
import app.latchway.core.KeySchedule
import app.latchway.core.Msg
import app.latchway.core.Opener
import app.latchway.core.Proof
import app.latchway.core.Protocol
import app.latchway.core.Sealer
import app.latchway.core.Share
import app.latchway.core.Wire
import app.latchway.core.randomBytes
import app.latchway.rendezvous.Inbound
import app.latchway.rendezvous.Rendezvous
import app.latchway.rendezvous.RendezvousException
import app.latchway.rendezvous.RendezvousUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import java.io.OutputStream

class ReceiveOptions(
    val share: Share,
    val host: String,
    /** Overrides the rendezvous derived from the link's host (local testing). */
    val rendezvous: HttpUrl? = null,
    val password: String = "",
    val askPassword: (suspend () -> String?)? = null,
    /**
     * Called once the file is known; returns where the bytes go, or null to
     * decline. `approval` says the sender will be asked first.
     */
    val accept: suspend (meta: FileMeta, approval: Boolean) -> OutputStream?,
    val relayOnly: Boolean = false,
    val onEvent: (Event) -> Unit = {},
)

class ReceiveResult(val meta: FileMeta, val bytes: Long)

private const val HANDSHAKE_TIMEOUT_MS = 90_000L
private const val OFFER_TIMEOUT_MS = 10 * 60_000L
private const val CLOSE_GRACE_MS = 5_000L

object Receiver {
    /**
     * Joins a share and downloads its file. Throws SessionException with
     * the protocol code (bad_auth, host_gone, denied, …), RendezvousException
     * for a refused join (not_found, busy), or InvalidLinkException.
     */
    suspend fun receive(opts: ReceiveOptions): ReceiveResult {
        val base = opts.rendezvous ?: RendezvousUrls.base("https://" + opts.host)
            ?: throw IllegalArgumentException("bad rendezvous host")
        val (conn, joined) = Rendezvous.dialJoiner(base, opts.share.idString)
        val sid = joined.sid ?: ""
        opts.onEvent(Event.Joined(sid))
        try {
            return session(conn.inbound, { conn.send(it) }, sid, opts)
        } finally {
            conn.close()
        }
    }

    private object Timeout

    private suspend fun session(
        inbound: kotlinx.coroutines.channels.Channel<Inbound>,
        send: (Msg) -> Unit,
        sid: String,
        opts: ReceiveOptions,
    ): ReceiveResult {
        fun sig(m: Msg) = send(Msg(t = Wire.T_SIG, sid = sid, d = m.encode()))

        suspend fun next(timeoutMs: Long): Msg {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val item = withTimeoutOrNull(maxOf(1, deadline - System.currentTimeMillis())) { inbound.receive() }
                    ?: throw SessionException(Wire.ERR_TIMEOUT)
                when (item) {
                    is Inbound.Failed -> throw item.error
                    is Inbound.Message -> {
                        if (item.msg.t != Wire.T_SIG) continue
                        val d = item.msg.d ?: continue
                        if (d.length > Protocol.MAX_SIGNAL_PAYLOAD) throw SessionException(Wire.ERR_PROTOCOL)
                        return Msg.decode(d) ?: throw SessionException(Wire.ERR_PROTOCOL)
                    }
                }
            }
        }

        // 1. hello.
        val hello = next(HANDSHAKE_TIMEOUT_MS)
        if (hello.t == Wire.T_ERROR) throw SessionException(hello.code ?: Wire.ERR_PROTOCOL)
        val hostNonce = hello.n?.let { B64.decode(it) }
        val pw = hello.pw
        if (hello.t != Wire.T_HELLO || hello.v != Protocol.VERSION || pw == null || hostNonce == null || hostNonce.size != Protocol.NONCE_LEN) {
            throw SessionException(Wire.ERR_PROTOCOL)
        }
        var password = if (pw) opts.password else ""
        if (pw && password.isEmpty()) {
            password = opts.askPassword?.invoke() ?: throw SessionException(Wire.ERR_CLOSED, "No password entered.")
        }
        val keys = withContext(Dispatchers.Default) { KeySchedule.derive(opts.share.id, opts.share.secret, password) }

        // 2. proof.
        val joinerNonce = randomBytes(Protocol.NONCE_LEN)
        val proof = Proof.compute(keys.auth, Protocol.VERSION, pw, hostNonce, joinerNonce)
        sig(Msg(t = Wire.T_AUTH, n = B64.encode(joinerNonce), p = B64.encode(proof)))

        // 3. session keys.
        val sk = KeySchedule.session(keys.root, hostNonce, joinerNonce)
        val sealer = Sealer(sk.sig, Direction.JOINER_TO_HOST)
        val opener = Opener(sk.sig, Direction.HOST_TO_JOINER)
        fun enc(m: Msg) = sig(Msg(t = Wire.T_ENC, c = sealer.seal(m.encodeBytes())))
        var encrypted = false
        suspend fun nextEnc(timeoutMs: Long): Msg {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val m = next(maxOf(1, deadline - System.currentTimeMillis()))
                if (m.t == Wire.T_ERROR && !encrypted) throw SessionException(m.code ?: Wire.ERR_PROTOCOL)
                if (m.t != Wire.T_ENC) continue
                val pt = m.c?.let { opener.open(it) }
                    ?: if (!encrypted) throw InvalidLinkException() else throw SessionException(Wire.ERR_PROTOCOL)
                encrypted = true
                return Msg.decode(pt) ?: throw SessionException(Wire.ERR_PROTOCOL)
            }
        }

        // 4. meta.
        val meta = nextEnc(HANDSHAKE_TIMEOUT_MS)
        if (meta.t == Wire.T_ERROR) throw SessionException(meta.code ?: Wire.ERR_PROTOCOL)
        val metaName = meta.name
        val metaSize = meta.size
        if (meta.t != Wire.T_META || meta.chunk != Protocol.CHUNK_SIZE || metaSize == null || metaName.isNullOrEmpty()) {
            throw SessionException(Wire.ERR_PROTOCOL)
        }
        val fileMeta = FileMeta(metaName, metaSize, meta.mime ?: "application/octet-stream", meta.from)
        val approval = meta.approval == true
        val out = opts.accept(fileMeta, approval) ?: run {
            enc(Msg(t = Wire.T_ERROR, code = Wire.ERR_CLOSED))
            throw SessionException(Wire.ERR_CLOSED, "declined")
        }
        if (approval) opts.onEvent(Event.ApprovalWait(sid))

        try {
            // 5. go.
            val go = nextEnc(OFFER_TIMEOUT_MS)
            if (go.t == Wire.T_ERROR) {
                opts.onEvent(Event.Denied(sid, go.code ?: Wire.ERR_PROTOCOL))
                throw SessionException(go.code ?: Wire.ERR_PROTOCOL)
            }
            if (go.t != Wire.T_GO) throw SessionException(Wire.ERR_PROTOCOL)
            opts.onEvent(Event.Connecting(sid))

            // 6. offer, on the ICE list from `go`.
            val peer = Peer.create(go.ice ?: emptyList(), opts.relayOnly)
            try {
                val offer = peer.createOffer()
                peer.setLocal(offer)
                enc(Msg(t = Wire.T_OFFER, sdp = peer.pc.localDescription?.description ?: offer.description, start = 0))

                // 7. answer, ICE, chunks.
                val chunks = ChunkOpener(sk.file)
                var open = false
                var finishing = false
                var received = 0L
                var lastReport = 0L
                var rendezvousDead = false
                val start = System.currentTimeMillis()
                var graceStart = 0L
                while (true) {
                    val item: Any = select {
                        if (!rendezvousDead) inbound.onReceive { it }
                        peer.events.onReceive { it }
                        if (!open) onTimeout(maxOf(1, HANDSHAKE_TIMEOUT_MS - (System.currentTimeMillis() - start))) { Timeout }
                        if (finishing) onTimeout(maxOf(1, CLOSE_GRACE_MS - (System.currentTimeMillis() - graceStart))) { Timeout }
                    }
                    when (item) {
                        Timeout -> {
                            if (finishing) return finish(sid, fileMeta, received, opts)
                            throw SessionException(Wire.ERR_TIMEOUT)
                        }
                        is Inbound.Failed -> {
                            // The rendezvous no longer matters once the channel is open.
                            if (!open) throw item.error
                            rendezvousDead = true
                        }
                        is Inbound.Message -> {
                            val m = item.msg
                            if (m.t != Wire.T_SIG) continue
                            val pm = m.d?.let { Msg.decode(it) } ?: continue
                            if (pm.t != Wire.T_ENC) continue
                            val pt = pm.c?.let { opener.open(it) }
                            if (pt == null) {
                                if (open) continue
                                throw SessionException(Wire.ERR_PROTOCOL)
                            }
                            val sm = Msg.decode(pt) ?: continue
                            when (sm.t) {
                                Wire.T_ANSWER -> peer.setRemote(SessionDescription(SessionDescription.Type.ANSWER, sm.sdp ?: ""))
                                Wire.T_ICE -> peer.addCandidate(sm)
                                Wire.T_ICE_DONE -> {}
                                Wire.T_ERROR -> if (!open) throw SessionException(sm.code ?: Wire.ERR_PROTOCOL)
                            }
                        }
                        is PeerEvent -> when (item) {
                            is PeerEvent.Candidate -> enc(candidateMsg(item.candidate))
                            PeerEvent.GatheringDone -> enc(Msg(t = Wire.T_ICE_DONE))
                            is PeerEvent.State -> when (item.state) {
                                PeerConnection.PeerConnectionState.FAILED,
                                PeerConnection.PeerConnectionState.CLOSED,
                                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                                    if (finishing) return finish(sid, fileMeta, received, opts)
                                    throw SessionException(Wire.ERR_PROTOCOL, "connection ${item.state}")
                                }
                                else -> {}
                            }
                            PeerEvent.ChannelOpen -> {
                                open = true
                                opts.onEvent(Event.Connected(sid))
                            }
                            PeerEvent.ChannelClosed -> {
                                if (finishing) return finish(sid, fileMeta, received, opts)
                                throw SessionException(Wire.ERR_PROTOCOL, "channel closed")
                            }
                            is PeerEvent.Text -> {
                                if (finishing) continue
                                val cm = Msg.decode(item.text)
                                if (cm?.t == Wire.T_ABORT) throw SessionException(Wire.ERR_CLOSED, "sender aborted: ${cm.reason}")
                            }
                            is PeerEvent.Binary -> {
                                if (finishing) continue
                                val r = try {
                                    chunks.open(item.data)
                                } catch (e: Exception) {
                                    peer.sendText(Msg(t = Wire.T_ABORT, reason = "bad chunk"))
                                    throw SessionException(Wire.ERR_PROTOCOL, "chunk ${chunks.next}: ${e.message}")
                                }
                                if (fileMeta.size >= 0 && received + r.plaintext.size > fileMeta.size) {
                                    peer.sendText(Msg(t = Wire.T_ABORT, reason = "too much data"))
                                    throw SessionException(Wire.ERR_PROTOCOL, "more than the announced size")
                                }
                                if (r.plaintext.isNotEmpty()) {
                                    try {
                                        withContext(Dispatchers.IO) { out.write(r.plaintext) }
                                    } catch (e: Exception) {
                                        peer.sendText(Msg(t = Wire.T_ABORT, reason = "write error"))
                                        throw e
                                    }
                                }
                                received += r.plaintext.size
                                if (received - lastReport >= PROGRESS_EVERY || r.last) {
                                    lastReport = received
                                    peer.sendText(Msg(t = Wire.T_PROGRESS, bytes = received))
                                    opts.onEvent(Event.Progress(sid, received, fileMeta.size))
                                }
                                if (r.last) {
                                    if (fileMeta.size >= 0 && received != fileMeta.size) {
                                        peer.sendText(Msg(t = Wire.T_ABORT, reason = "size mismatch"))
                                        throw SessionException(Wire.ERR_PROTOCOL, "received $received of ${fileMeta.size}")
                                    }
                                    withContext(Dispatchers.IO) {
                                        out.flush()
                                        out.close()
                                    }
                                    peer.sendText(Msg(t = Wire.T_DONE))
                                    finishing = true
                                    graceStart = System.currentTimeMillis()
                                }
                            }
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) { peer.close() }
            }
        } finally {
            try {
                withContext(NonCancellable + Dispatchers.IO) { out.close() }
            } catch (_: Exception) {
            }
        }
    }

    private fun finish(sid: String, meta: FileMeta, bytes: Long, opts: ReceiveOptions): ReceiveResult {
        opts.onEvent(Event.Done(sid, bytes))
        return ReceiveResult(meta, bytes)
    }
}
