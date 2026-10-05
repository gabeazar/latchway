// Runs downloads independently of the UI, like ShareManager does for
// shares. The screen asks the user for a password or a destination when
// the transfer needs one; the transfer waits on a deferred meanwhile.
package app.latchway.receive

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.latchway.core.Share
import app.latchway.peer.Event
import app.latchway.peer.FileMeta
import app.latchway.peer.InvalidLinkException
import app.latchway.peer.ReceiveOptions
import app.latchway.peer.Receiver
import app.latchway.peer.SessionException
import app.latchway.rendezvous.RendezvousException
import app.latchway.rendezvous.RendezvousUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

enum class ReceivePhase {
    CONNECTING, NEED_PASSWORD, NEED_DESTINATION, WAITING_APPROVAL, CONNECTING_PEER, TRANSFERRING,
    DONE, UNPACKING, FAILED, CANCELLED,
}

data class ActiveReceive(
    val id: String,
    val link: String,
    val host: String,
    val phase: ReceivePhase,
    val meta: FileMeta? = null,
    val approval: Boolean = false,
    val bytes: Long = 0,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val destination: Uri? = null,
    val unpackedTo: Uri? = null,
    val error: String? = null,
)

object ReceiveManager {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _receives = MutableStateFlow<List<ActiveReceive>>(emptyList())
    val receives: StateFlow<List<ActiveReceive>> = _receives

    private val jobs = HashMap<String, Job>()
    private val passwords = HashMap<String, CompletableDeferred<String?>>()
    private val destinations = HashMap<String, CompletableDeferred<Uri?>>()

    fun attach(context: Context) {
        app = context.applicationContext
    }

    fun get(id: String): ActiveReceive? = _receives.value.firstOrNull { it.id == id }

    private fun update(id: String, fn: (ActiveReceive) -> ActiveReceive) {
        _receives.update { list -> list.map { if (it.id == id) fn(it) else it } }
    }

    fun anyActive(): Boolean = _receives.value.any { it.phase.isActive() }

    fun ReceivePhase.isActive() = this != ReceivePhase.DONE && this != ReceivePhase.FAILED && this != ReceivePhase.CANCELLED

    /**
     * Starts a download for a link. Returns the receive id, or null when
     * the text is not a complete Latchway link. rendezvousOverride points
     * at a local server for development.
     */
    fun start(link: String, rendezvousOverride: String? = null, relayOnly: Boolean = false): String? {
        val parsed = Share.parseLink(link) ?: return null
        // The same link opened twice is one download.
        _receives.value.firstOrNull { it.link == link.trim() && it.phase.isActive() }?.let { return it.id }
        val id = UUID.randomUUID().toString()
        _receives.update { it + ActiveReceive(id = id, link = link.trim(), host = parsed.host, phase = ReceivePhase.CONNECTING) }
        refreshService(app)
        jobs[id] = scope.launch {
            try {
                run(id, parsed, rendezvousOverride, relayOnly)
            } catch (e: CancellationException) {
                update(id) { it.copy(phase = ReceivePhase.CANCELLED) }
            } catch (e: Throwable) {
                update(id) { it.copy(phase = ReceivePhase.FAILED, error = explain(e)) }
            } finally {
                jobs.remove(id)
                synchronized(passwords) { passwords.remove(id) }
                synchronized(destinations) { destinations.remove(id) }
                refreshService(app)
            }
        }
        return id
    }

    private suspend fun run(id: String, parsed: Share.Companion.Parsed, rendezvousOverride: String?, relayOnly: Boolean) {
        val result = Receiver.receive(
            ReceiveOptions(
                share = parsed.share,
                host = parsed.host,
                rendezvous = rendezvousOverride?.let { RendezvousUrls.base(it) },
                askPassword = {
                    val d = CompletableDeferred<String?>()
                    synchronized(passwords) { passwords[id] = d }
                    update(id) { it.copy(phase = ReceivePhase.NEED_PASSWORD) }
                    val pw = d.await()
                    update(id) { it.copy(phase = ReceivePhase.CONNECTING) }
                    pw
                },
                accept = { meta, approval -> chooseDestination(id, meta, approval) },
                relayOnly = relayOnly,
                onEvent = { onEvent(id, it) },
            ),
        )
        update(id) { it.copy(phase = ReceivePhase.DONE, bytes = result.bytes, finishedAt = System.currentTimeMillis()) }
    }

    private suspend fun chooseDestination(id: String, meta: FileMeta, approval: Boolean): OutputStream? {
        val d = CompletableDeferred<Uri?>()
        synchronized(destinations) { destinations[id] = d }
        update(id) { it.copy(phase = ReceivePhase.NEED_DESTINATION, meta = meta, approval = approval) }
        val uri = d.await() ?: return null
        update(id) { it.copy(phase = if (approval) ReceivePhase.WAITING_APPROVAL else ReceivePhase.CONNECTING_PEER, destination = uri) }
        return withContext(Dispatchers.IO) {
            app.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("Cannot write to the chosen location")
        }
    }

    private fun onEvent(id: String, e: Event) {
        when (e) {
            is Event.Connecting -> update(id) { it.copy(phase = ReceivePhase.CONNECTING_PEER) }
            is Event.Connected -> update(id) { it.copy(phase = ReceivePhase.TRANSFERRING, startedAt = System.currentTimeMillis()) }
            is Event.Progress -> update(id) { it.copy(bytes = e.bytes) }
            is Event.Denied -> update(id) { it.copy(error = e.code) }
            else -> {}
        }
        refreshService(app)
    }

    fun providePassword(id: String, password: String?) {
        synchronized(passwords) { passwords[id] }?.complete(password)
    }

    fun provideDestination(id: String, uri: Uri?) {
        synchronized(destinations) { destinations[id] }?.complete(uri)
    }

    fun cancel(id: String) {
        jobs[id]?.cancel()
        synchronized(passwords) { passwords[id] }?.complete(null)
        synchronized(destinations) { destinations[id] }?.complete(null)
    }

    fun dismiss(id: String) {
        cancel(id)
        _receives.update { list -> list.filterNot { it.id == id } }
        refreshService(app)
    }

    /** Extracts a received zip into a folder the user picked. */
    fun unpack(id: String, tree: Uri) {
        val r = get(id) ?: return
        val src = r.destination ?: return
        update(id) { it.copy(phase = ReceivePhase.UNPACKING) }
        scope.launch(Dispatchers.IO) {
            try {
                val dir = DocumentFile.fromTreeUri(app, tree) ?: throw IllegalStateException("Cannot open the folder")
                app.contentResolver.openInputStream(src)!!.use { input ->
                    ZipInputStream(input).use { zin ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val e = zin.nextEntry ?: break
                            if (e.isDirectory) continue
                            val name = e.name.substringAfterLast('/')
                            val mime = app.contentResolver.getType(src)?.takeIf { false }
                                ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                                ?: "application/octet-stream"
                            val out = dir.createFile(mime, name) ?: throw IllegalStateException("Cannot create $name")
                            app.contentResolver.openOutputStream(out.uri, "wt")!!.use { o ->
                                while (true) {
                                    val n = zin.read(buf)
                                    if (n < 0) break
                                    o.write(buf, 0, n)
                                }
                            }
                        }
                    }
                }
                update(id) { it.copy(phase = ReceivePhase.DONE, unpackedTo = tree) }
            } catch (e: Throwable) {
                update(id) { it.copy(phase = ReceivePhase.DONE, error = "Unpacking failed: ${e.message}") }
            }
        }
    }

    fun refreshService(context: Context) {
        val intent = android.content.Intent(context, ReceiveService::class.java)
        if (anyActive()) {
            try {
                context.startForegroundService(intent)
            } catch (_: Exception) {
            }
        } else {
            context.stopService(intent)
        }
    }

    private fun explain(e: Throwable): String = when {
        e is InvalidLinkException -> "This link is not valid: the key after # does not match what the sender is serving."
        e is SessionException -> when (e.code) {
            "bad_auth" -> "Wrong password."
            "host_gone" -> "The sender went offline before the transfer finished."
            "denied" -> "The sender declined."
            "expired" -> "This link has expired."
            "busy" -> "The sender is busy with another download. Try again shortly."
            "timeout" -> "Timed out waiting for the sender."
            "closed" -> e.message ?: "The transfer was closed."
            else -> e.message ?: "Transfer failed (${e.code})."
        }
        e is RendezvousException -> when (e.code) {
            "not_found" -> "The sender is not online. Latchway needs to be running on their device."
            "busy" -> "Too many people are connected to this share right now."
            "host_gone" -> "The sender went offline."
            else -> "Rendezvous error: ${e.code}"
        }
        else -> e.message ?: "Something went wrong."
    }
}
