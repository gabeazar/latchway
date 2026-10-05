// Keeps every active share alive, independent of any screen: the UI only
// observes state here, and ShareService keeps the process in the
// foreground while anything is being shared.
package app.latchway.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import app.latchway.core.Share
import app.latchway.core.ZipStream
import app.latchway.core.randomBytes
import app.latchway.core.Protocol
import app.latchway.data.Settings
import app.latchway.peer.Event
import app.latchway.peer.FileMeta
import app.latchway.peer.Host
import app.latchway.peer.HostOptions
import app.latchway.rendezvous.RendezvousException
import app.latchway.rendezvous.RendezvousUrls
import app.latchway.util.NetworkWatcher
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
import java.io.InputStream
import java.util.UUID

/** What the user asked to share. */
data class ShareRequest(
    val uris: List<Uri>,
    val password: String = "",
    val askApproval: Boolean = false,
    val maxDownloads: Int = 1,
    val expiresInHours: Int = 24,
    val relayOnly: Boolean = false,
    val from: String = "",
    val rendezvous: String = Settings.DEFAULT_RENDEZVOUS,
)

enum class SharePhase { STARTING, WAITING, OFFLINE, TRANSFERRING, FINISHED, EXPIRED, STOPPED, FAILED }

data class ReceiverState(
    val sid: String,
    val phase: String,
    val bytes: Long = 0,
    val startedAt: Long = 0,
)

data class ActiveShare(
    val id: String,
    val link: String,
    val meta: FileMeta,
    val fileCount: Int,
    val phase: SharePhase,
    val passwordProtected: Boolean,
    val askApproval: Boolean,
    val maxDownloads: Int,
    val expiresAt: Long,
    val createdAt: Long,
    val completed: Int = 0,
    val receivers: Map<String, ReceiverState> = emptyMap(),
    val error: String? = null,
)

data class PendingApproval(val shareId: String, val sid: String, val requestedAt: Long)

object ShareManager {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _shares = MutableStateFlow<List<ActiveShare>>(emptyList())
    val shares: StateFlow<List<ActiveShare>> = _shares

    private val _approvals = MutableStateFlow<List<PendingApproval>>(emptyList())
    val approvals: StateFlow<List<PendingApproval>> = _approvals

    private class Running(val job: Job) {
        @Volatile
        var host: Host? = null
    }

    private val running = HashMap<String, Running>()
    private val decisions = HashMap<Pair<String, String>, CompletableDeferred<Boolean>>()

    fun attach(context: Context) {
        app = context.applicationContext
        NetworkWatcher.start(app) {
            synchronized(running) { running.values.forEach { it.host?.wake?.trySend(Unit) } }
        }
    }

    fun get(id: String): ActiveShare? = _shares.value.firstOrNull { it.id == id }

    private fun updateShare(id: String, fn: (ActiveShare) -> ActiveShare) {
        _shares.update { list -> list.map { if (it.id == id) fn(it) else it } }
    }

    /** Starts sharing and returns the share's id for the UI to follow. */
    fun start(req: ShareRequest): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        _shares.update {
            it + ActiveShare(
                id = id, link = "", meta = FileMeta("", -1, ""), fileCount = req.uris.size, phase = SharePhase.STARTING,
                passwordProtected = req.password.isNotEmpty(), askApproval = req.askApproval,
                maxDownloads = req.maxDownloads, expiresAt = if (req.expiresInHours > 0) now + req.expiresInHours * 3_600_000L else 0,
                createdAt = now,
            )
        }
        ServiceControl.refresh(app)
        val job = scope.launch {
            try {
                runShare(id, req)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) {
                    updateShare(id) { it.copy(phase = SharePhase.STOPPED) }
                } else {
                    updateShare(id) { it.copy(phase = SharePhase.FAILED, error = describe(e)) }
                }
            } finally {
                synchronized(running) { running.remove(id) }
                ServiceControl.refresh(app)
            }
        }
        synchronized(running) { running[id] = Running(job) }
        return id
    }

    private suspend fun runShare(id: String, req: ShareRequest) {
        val base = RendezvousUrls.base(req.rendezvous) ?: throw IllegalArgumentException("Bad rendezvous address: ${req.rendezvous}")
        val files = withContext(Dispatchers.IO) { req.uris.map { describe(it) } }
        val (meta, open) = source(files, req.from)
        val share = Share.create()
        val regKey = randomBytes(Protocol.REG_KEY_LEN)
        val expiresAt = get(id)?.expiresAt ?: 0

        val host = Host(
            HostOptions(
                base = base, share = share, regKey = regKey, password = req.password, meta = meta, open = open,
                approve = if (req.askApproval) { sid -> awaitApproval(id, sid) } else null,
                maxDownloads = req.maxDownloads, maxConcurrent = 0, expiresAt = expiresAt, relayOnly = req.relayOnly,
                onEvent = { onEvent(id, it) },
            ),
        )
        synchronized(running) { running[id]?.host = host }
        updateShare(id) { it.copy(link = host.link, meta = meta, phase = SharePhase.WAITING) }
        ServiceControl.refresh(app)

        val outcome = try {
            host.run()
        } catch (e: RendezvousException) {
            throw IllegalStateException(
                when (e.code) {
                    "forbidden" -> "The rendezvous refused this share (another device holds its id, or a token is required)."
                    "replaced" -> "Another device registered this share."
                    else -> "Rendezvous error: ${e.code}"
                },
            )
        }
        updateShare(id) {
            it.copy(
                phase = when (outcome) {
                    Host.Outcome.LIMIT_REACHED -> SharePhase.FINISHED
                    Host.Outcome.EXPIRED -> SharePhase.EXPIRED
                    Host.Outcome.CANCELLED -> SharePhase.STOPPED
                },
                completed = host.completedCount,
            )
        }
    }

    private fun onEvent(id: String, e: Event) {
        when (e) {
            is Event.Registered -> updateShare(id) { s ->
                if (e.error == null) s.copy(phase = if (s.receivers.any { it.value.phase == "transferring" }) SharePhase.TRANSFERRING else SharePhase.WAITING)
                else s.copy(phase = SharePhase.OFFLINE)
            }
            is Event.Joined -> updateShare(id) { it.copy(receivers = it.receivers + (e.sid to ReceiverState(e.sid, "checking the key"))) }
            is Event.AuthFailed -> updateShare(id) { it.copy(receivers = it.receivers - e.sid) }
            is Event.ApprovalWait -> updateShare(id) { it.copy(receivers = it.receivers + (e.sid to ReceiverState(e.sid, "waiting for your approval"))) }
            is Event.Denied -> updateShare(id) { it.copy(receivers = it.receivers - e.sid) }
            is Event.Connecting -> updateShare(id) { it.copy(receivers = it.receivers + (e.sid to ReceiverState(e.sid, "connecting"))) }
            is Event.Connected -> updateShare(id) {
                it.copy(phase = SharePhase.TRANSFERRING, receivers = it.receivers + (e.sid to ReceiverState(e.sid, "transferring", 0, System.currentTimeMillis())))
            }
            is Event.Progress -> updateShare(id) { s ->
                val r = s.receivers[e.sid] ?: ReceiverState(e.sid, "transferring", 0, System.currentTimeMillis())
                s.copy(receivers = s.receivers + (e.sid to r.copy(bytes = e.bytes)))
            }
            is Event.Done -> updateShare(id) { s ->
                val rest = s.receivers - e.sid
                s.copy(completed = s.completed + 1, receivers = rest, phase = if (rest.any { it.value.phase == "transferring" }) SharePhase.TRANSFERRING else SharePhase.WAITING)
            }
            is Event.Failed, is Event.Left -> {
                val sid = if (e is Event.Failed) e.sid else (e as Event.Left).sid
                updateShare(id) { s ->
                    val rest = s.receivers - sid
                    s.copy(receivers = rest, phase = if (s.phase == SharePhase.TRANSFERRING && rest.none { it.value.phase == "transferring" }) SharePhase.WAITING else s.phase)
                }
            }
            Event.Stopped -> {}
        }
        ServiceControl.refresh(app)
    }

    private suspend fun awaitApproval(id: String, sid: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        synchronized(decisions) { decisions[id to sid] = d }
        _approvals.update { it + PendingApproval(id, sid, System.currentTimeMillis()) }
        ServiceControl.refresh(app)
        try {
            return d.await()
        } finally {
            synchronized(decisions) { decisions.remove(id to sid) }
            _approvals.update { list -> list.filterNot { it.shareId == id && it.sid == sid } }
            ServiceControl.refresh(app)
        }
    }

    fun decide(shareId: String, sid: String, allow: Boolean) {
        synchronized(decisions) { decisions[shareId to sid] }?.complete(allow)
    }

    fun stop(id: String) {
        synchronized(running) { running[id] }?.job?.cancel()
        updateShare(id) { if (it.phase.isActive()) it.copy(phase = SharePhase.STOPPED) else it }
    }

    fun stopAll() {
        synchronized(running) { running.keys.toList() }.forEach { stop(it) }
    }

    fun dismiss(id: String) {
        stop(id)
        _shares.update { list -> list.filterNot { it.id == id } }
        ServiceControl.refresh(app)
    }

    fun SharePhase.isActive() = this == SharePhase.STARTING || this == SharePhase.WAITING || this == SharePhase.OFFLINE || this == SharePhase.TRANSFERRING

    fun anyActive(): Boolean = _shares.value.any { it.phase.isActive() }

    // --- files -------------------------------------------------------------------

    class Described(val uri: Uri, val name: String, val size: Long, val mime: String)

    private fun describe(uri: Uri): Described {
        var name: String? = null
        var size = -1L
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (size < 0) {
            // Some providers omit SIZE; measure through a descriptor.
            app.contentResolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
        }
        if (size < 0) throw IllegalArgumentException("Cannot determine the size of ${name ?: uri.lastPathSegment}")
        val n = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val mime = app.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(n.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        return Described(uri, n, size, mime)
    }

    private fun source(files: List<Described>, from: String): Pair<FileMeta, () -> InputStream> {
        require(files.isNotEmpty()) { "nothing to share" }
        val sender = from.takeIf { it.isNotBlank() }
        if (files.size == 1) {
            val f = files[0]
            return FileMeta(f.name, f.size, f.mime, sender) to {
                app.contentResolver.openInputStream(f.uri) ?: throw IllegalStateException("cannot open ${f.name}")
            }
        }
        val names = ZipStream.uniqueNames(files.map { it.name })
        val entries = files.mapIndexed { i, f ->
            ZipStream.Entry(names[i], f.size) { app.contentResolver.openInputStream(f.uri) ?: throw IllegalStateException("cannot open ${f.name}") }
        }
        val total = ZipStream(entries).totalSize
        val zipName = "${files.size} files.zip"
        return FileMeta(zipName, total, "application/zip", sender) to { ZipStream(entries) }
    }

    private fun describe(e: Throwable): String = e.message ?: e::class.simpleName ?: "error"

    /** Share-sheet intents land here. */
    fun urisFrom(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtraCompat(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtraCompat(Intent.EXTRA_STREAM)
        else -> emptyList()
    }
}

@Suppress("DEPRECATION")
private fun Intent.getParcelableExtraCompat(key: String): Uri? =
    if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Uri::class.java) else getParcelableExtra(key)

@Suppress("DEPRECATION")
private fun Intent.getParcelableArrayListExtraCompat(key: String): List<Uri> =
    (if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, Uri::class.java) else getParcelableArrayListExtra(key)) ?: emptyList()
