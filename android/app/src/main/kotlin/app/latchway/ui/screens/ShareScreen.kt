package app.latchway.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.latchway.share.ShareManager
import app.latchway.share.SharePhase
import app.latchway.ui.Card
import app.latchway.ui.Dot
import app.latchway.ui.Eyebrow
import app.latchway.ui.Note
import app.latchway.ui.QrCode
import app.latchway.ui.StatusLine
import app.latchway.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(id: String, onBack: () -> Unit) {
    val shares by ShareManager.shares.collectAsState()
    val approvals by ShareManager.approvals.collectAsState()
    val s = shares.firstOrNull { it.id == id }
    val context = LocalContext.current
    val active = s != null && with(ShareManager) { s.phase.isActive() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your link") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        if (s == null) {
            Text("This share is gone.", Modifier.padding(pad).padding(20.dp))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.meta.name.ifEmpty { "Preparing…" }, style = MaterialTheme.typography.titleMedium)
                    val details = buildList {
                        if (s.meta.size >= 0) add(Format.bytes(s.meta.size))
                        if (s.fileCount > 1) add("${s.fileCount} files")
                        if (s.passwordProtected) add("password")
                        if (s.askApproval) add("approval")
                        add(if (s.maxDownloads == 0) "no download limit" else "${s.maxDownloads} download${if (s.maxDownloads == 1) "" else "s"}")
                        if (s.expiresAt > 0) add("expires in " + Format.duration((s.expiresAt - System.currentTimeMillis()).coerceAtLeast(0)))
                    }
                    Text(details.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            val transferring = s.receivers.values.filter { it.phase == "transferring" }
            val (text, dot) = when (s.phase) {
                SharePhase.STARTING -> "Registering the link…" to Dot.BUSY
                SharePhase.WAITING -> "Online. Waiting for someone to open the link." to Dot.GOOD
                SharePhase.OFFLINE -> "No connection to the rendezvous; retrying." to Dot.BAD
                SharePhase.TRANSFERRING -> "Sending…" to Dot.BUSY
                SharePhase.FINISHED -> "Done. ${s.completed} download${if (s.completed == 1) "" else "s"} completed; the link is now dead." to Dot.IDLE
                SharePhase.EXPIRED -> "Expired. The link no longer works." to Dot.IDLE
                SharePhase.STOPPED -> "Stopped. The link no longer works." to Dot.IDLE
                SharePhase.FAILED -> (s.error ?: "Failed") to Dot.BAD
            }
            StatusLine(text, dot)

            if (s.link.isNotEmpty() && active) {
                Eyebrow("Send this link")
                QrCode(s.link)
                Note(s.link)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm.setPrimaryClip(ClipData.newPlainText("Latchway link", s.link).apply {
                                description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                            })
                            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Copy") }
                    OutlinedButton(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, s.link)
                                putExtra(Intent.EXTRA_SUBJECT, "A file for you: ${s.meta.name}")
                            }
                            context.startActivity(Intent.createChooser(send, "Send the link with"))
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Share…") }
                }
                Text(
                    "Anyone with the complete link can download the file while this share is active. The part after # is the key; it never reaches a server.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            approvals.filter { it.shareId == id }.forEach { a ->
                Card {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Someone opened the link and wants the file.", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { ShareManager.decide(id, a.sid, true) }) { Text("Send it") }
                            OutlinedButton(onClick = { ShareManager.decide(id, a.sid, false) }) { Text("Don't") }
                        }
                    }
                }
            }

            if (s.receivers.isNotEmpty()) {
                Eyebrow("Receivers")
                s.receivers.values.forEach { r ->
                    Card {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(r.phase.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
                            if (r.phase == "transferring") {
                                val elapsed = System.currentTimeMillis() - r.startedAt
                                val line = buildString {
                                    append(Format.bytes(r.bytes))
                                    if (s.meta.size > 0) append(" of ").append(Format.bytes(s.meta.size))
                                    if (elapsed > 1000) append("  ·  ").append(Format.rate(r.bytes, elapsed))
                                    Format.eta(r.bytes, s.meta.size, elapsed)?.let { append("  ·  ").append(it) }
                                }
                                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (s.meta.size > 0) LinearProgressIndicator(progress = { r.bytes.toFloat() / s.meta.size }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }

            if (s.completed > 0 && active) Text("${s.completed} download${if (s.completed == 1) "" else "s"} completed so far.", style = MaterialTheme.typography.bodyMedium)

            if (active) {
                OutlinedButton(onClick = { ShareManager.stop(id) }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Stop sharing") }
            } else {
                OutlinedButton(onClick = { ShareManager.dismiss(id); onBack() }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Remove") }
            }
        }
    }
}
