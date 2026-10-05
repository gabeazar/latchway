// libwebrtc plumbing shared by the host and joiner sessions: factory setup,
// the negotiated data channel, and callbacks turned into a channel of
// events a single coroutine can consume.
package app.latchway.peer

import android.content.Context
import app.latchway.core.IceServer
import app.latchway.core.Msg
import app.latchway.core.Wire
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val DATA_CHANNEL_LABEL = "latchway"
const val HIGH_WATER = 1L shl 20
const val LOW_WATER = 256L shl 10
const val PROGRESS_EVERY = 1L shl 20

object WebRtc {
    @Volatile
    private var factory: PeerConnectionFactory? = null

    fun init(context: Context) {
        if (factory != null) return
        synchronized(this) {
            if (factory != null) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions(),
            )
            factory = PeerConnectionFactory.builder()
                .setOptions(PeerConnectionFactory.Options())
                .createPeerConnectionFactory()
        }
    }

    fun factory(): PeerConnectionFactory = factory ?: error("WebRtc.init not called")

    fun config(servers: List<IceServer>, relayOnly: Boolean): PeerConnection.RTCConfiguration {
        val ice = servers.map { s ->
            PeerConnection.IceServer.builder(s.urls).apply {
                if (!s.username.isNullOrEmpty()) setUsername(s.username)
                if (!s.credential.isNullOrEmpty()) setPassword(s.credential)
            }.createIceServer()
        }
        return PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = if (relayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
    }
}

/** What the session loop sees from libwebrtc. */
sealed class PeerEvent {
    data class Candidate(val candidate: IceCandidate) : PeerEvent()
    object GatheringDone : PeerEvent()
    data class State(val state: PeerConnection.PeerConnectionState) : PeerEvent()
    object ChannelOpen : PeerEvent()
    object ChannelClosed : PeerEvent()
    data class Binary(val data: ByteArray) : PeerEvent()
    data class Text(val text: String) : PeerEvent()
}

/** A peer connection with its negotiated data channel and event stream. */
class Peer private constructor(
    val pc: PeerConnection,
    val dc: DataChannel,
    val events: Channel<PeerEvent>,
    /** Signalled when bufferedAmount drops to LOW_WATER (flow control). */
    val bufferedLow: Channel<Unit>,
) {
    fun sendBinary(frame: ByteArray): Boolean = dc.send(DataChannel.Buffer(ByteBuffer.wrap(frame), true))
    fun sendText(msg: Msg): Boolean = dc.send(DataChannel.Buffer(ByteBuffer.wrap(msg.encodeBytes()), false))
    fun bufferedAmount(): Long = dc.bufferedAmount()

    fun close() {
        try {
            dc.unregisterObserver()
            dc.close()
            dc.dispose()
        } catch (_: Exception) {
        }
        try {
            pc.close()
            pc.dispose()
        } catch (_: Exception) {
        }
        events.close()
    }

    suspend fun createOffer(): SessionDescription = sdp { pc.createOffer(it, MediaConstraints()) }
    suspend fun createAnswer(): SessionDescription = sdp { pc.createAnswer(it, MediaConstraints()) }
    suspend fun setLocal(d: SessionDescription) = setSdp { pc.setLocalDescription(it, d) }
    suspend fun setRemote(d: SessionDescription) = setSdp { pc.setRemoteDescription(it, d) }

    fun addCandidate(m: Msg) {
        val cand = m.cand ?: return
        pc.addIceCandidate(IceCandidate(m.mid ?: "", m.mline ?: 0, cand))
    }

    private suspend fun sdp(call: (SdpObserver) -> Unit): SessionDescription = suspendCancellableCoroutine { cont ->
        call(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) { if (cont.isActive) cont.resume(d) }
            override fun onCreateFailure(reason: String?) { if (cont.isActive) cont.resumeWithException(PeerException("sdp: $reason")) }
            override fun onSetSuccess() {}
            override fun onSetFailure(reason: String?) {}
        })
    }

    private suspend fun setSdp(call: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { cont ->
        call(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) {}
            override fun onCreateFailure(reason: String?) {}
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onSetFailure(reason: String?) { if (cont.isActive) cont.resumeWithException(PeerException("sdp: $reason")) }
        })
    }

    companion object {
        /**
         * Creates the connection and the negotiated channel (id 0, ordered,
         * reliable) and wires every callback into [events]. The receive
         * side blocks libwebrtc's thread when the consumer falls more than
         * 256 frames behind, which is the back-pressure that keeps a slow
         * disk from filling memory.
         */
        fun create(servers: List<IceServer>, relayOnly: Boolean): Peer {
            val events = Channel<PeerEvent>(256)
            val bufferedLow = Channel<Unit>(Channel.CONFLATED)
            val observer = object : PeerConnection.Observer {
                override fun onIceCandidate(c: IceCandidate) { events.trySendBlocking(PeerEvent.Candidate(c)) }
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
                    if (s == PeerConnection.IceGatheringState.COMPLETE) events.trySendBlocking(PeerEvent.GatheringDone)
                }
                override fun onConnectionChange(s: PeerConnection.PeerConnectionState) { events.trySendBlocking(PeerEvent.State(s)) }
                override fun onSignalingChange(s: PeerConnection.SignalingState) {}
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
                override fun onIceConnectionReceivingChange(b: Boolean) {}
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
                override fun onAddStream(s: MediaStream) {}
                override fun onRemoveStream(s: MediaStream) {}
                override fun onDataChannel(dc: DataChannel) {}
                override fun onRenegotiationNeeded() {}
            }
            val pc = WebRtc.factory().createPeerConnection(WebRtc.config(servers, relayOnly), observer)
                ?: throw PeerException("could not create peer connection")
            val init = DataChannel.Init().apply {
                negotiated = true
                id = 0
                ordered = true
            }
            val dc = pc.createDataChannel(DATA_CHANNEL_LABEL, init) ?: run {
                pc.dispose()
                throw PeerException("could not create data channel")
            }
            dc.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previous: Long) {
                    if (dc.bufferedAmount() <= LOW_WATER) bufferedLow.trySend(Unit)
                }
                override fun onStateChange() {
                    when (dc.state()) {
                        DataChannel.State.OPEN -> events.trySendBlocking(PeerEvent.ChannelOpen)
                        DataChannel.State.CLOSED -> events.trySend(PeerEvent.ChannelClosed)
                        else -> {}
                    }
                }
                override fun onMessage(buffer: DataChannel.Buffer) {
                    val data = ByteArray(buffer.data.remaining())
                    buffer.data.get(data)
                    if (buffer.binary) events.trySendBlocking(PeerEvent.Binary(data))
                    else events.trySendBlocking(PeerEvent.Text(String(data, Charsets.UTF_8)))
                }
            })
            return Peer(pc, dc, events, bufferedLow)
        }
    }
}

class PeerException(message: String) : Exception(message)

/** A protocol error code from the other side or the rendezvous. */
class SessionException(val code: String, message: String = "session: $code") : Exception(message)

/** The host's first encrypted message did not decrypt: wrong link or password. */
class InvalidLinkException : Exception("this link is not valid")

internal fun candidateMsg(c: IceCandidate) = Msg(t = Wire.T_ICE, cand = c.sdp, mid = c.sdpMid, mline = c.sdpMLineIndex)
