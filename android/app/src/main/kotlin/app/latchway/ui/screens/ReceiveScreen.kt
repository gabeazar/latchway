package app.latchway.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.latchway.LatchwayApp
import app.latchway.receive.ActiveReceive
import app.latchway.receive.ReceiveManager
import app.latchway.receive.ReceivePhase
import app.latchway.ui.Card
import app.latchway.ui.Dot
import app.latchway.ui.Eyebrow
import app.latchway.ui.Note
import app.latchway.ui.PendingLink
import app.latchway.ui.StatusLine
import app.latchway.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(onBack: () -> Unit) {
    val receives by ReceiveManager.receives.collectAsState()
    var currentId by remember { mutableStateOf<String?>(null) }
    var linkText by remember { mutableStateOf("") }
    var badLink by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(PendingLink.link) {
        PendingLink.link?.let { link ->
            PendingLink.link = null
            val s = LatchwayApp.instance.settings.current()
            val id = ReceiveManager.start(link, relayOnly = s.relayOnly)
            if (id == null) {
                linkText = link
                badLink = true
            } else currentId = id
        }
    }
    val r = receives.firstOrNull { it.id == currentId } ?: receives.firstOrNull { with(ReceiveManager) { it.phase.isActive() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receive") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (r == null) {
                Text("Paste the link you were sent.", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Links opened from a message usually land here by themselves. If one didn't, paste it below. The part after # is the key, so paste the whole thing.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = linkText, onValueChange = { linkText = it; badLink = false },
                    label = { Text("https://latchway.app/s/…#…") }, modifier = Modifier.fillMaxWidth(), isError = badLink,
                    supportingText = if (badLink) ({ Text("That isn't a complete Latchway link.") }) else null,
                )
                Button(
                    onClick = {
                        val id = ReceiveManager.start(linkText)
                        if (id == null) badLink = true else currentId = id
                    },
                    enabled = linkText.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.medium,
                ) { Text("Connect") }
            } else {
                ReceiveBody(r, onDone = { currentId = null })
            }
        }
    }
}

@Composable
private fun ReceiveBody(r: ActiveReceive, onDone: () -> Unit) {
    val context = LocalContext.current
    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(r.meta?.mime ?: "application/octet-stream")) { uri ->
        ReceiveManager.provideDestination(r.id, uri)
    }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) ReceiveManager.unpack(r.id, uri)
    }
    var password by remember { mutableStateOf("") }

    if (r.phase == ReceivePhase.NEED_PASSWORD) {
        AlertDialog(
            onDismissRequest = { ReceiveManager.providePassword(r.id, null) },
            title = { Text("This link has a password") },
            text = {
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), label = { Text("Password") },
                )
            },
            confirmButton = { TextButton(onClick = { ReceiveManager.providePassword(r.id, password) }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { ReceiveManager.providePassword(r.id, null) }) { Text("Cancel") } },
        )
    }

    Card {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(r.meta?.name ?: "Someone sent you a file", style = MaterialTheme.typography.titleMedium)
            r.meta?.let { m ->
                val details = buildList {
                    add(Format.bytes(m.size))
                    m.from?.takeIf { it.isNotBlank() }?.let { add("from $it") }
                    if (m.mime == "application/zip") add("zip")
                }
                Text(details.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("From " + r.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    val elapsed = System.currentTimeMillis() - r.startedAt
    val (text, dot) = when (r.phase) {
        ReceivePhase.CONNECTING -> "Connecting to the sender…" to Dot.BUSY
        ReceivePhase.NEED_PASSWORD -> "Waiting for the password" to Dot.BUSY
        ReceivePhase.NEED_DESTINATION -> "Choose where to save the file" to Dot.BUSY
        ReceivePhase.WAITING_APPROVAL -> "Waiting for the sender to approve" to Dot.BUSY
        ReceivePhase.CONNECTING_PEER -> "Connecting directly to their phone…" to Dot.BUSY
        ReceivePhase.TRANSFERRING -> buildString {
            append(Format.bytes(r.bytes))
            r.meta?.size?.takeIf { it > 0 }?.let { append(" of ").append(Format.bytes(it)) }
            if (elapsed > 1000) append("  ·  ").append(Format.rate(r.bytes, elapsed))
            Format.eta(r.bytes, r.meta?.size ?: -1, elapsed)?.let { append("  ·  ").append(it) }
        } to Dot.BUSY
        ReceivePhase.UNPACKING -> "Unpacking into the folder…" to Dot.BUSY
        ReceivePhase.DONE -> (if (r.unpackedTo != null) "Saved and unpacked." else "Saved.") to Dot.GOOD
        ReceivePhase.FAILED -> (r.error ?: "Failed") to Dot.BAD
        ReceivePhase.CANCELLED -> "Cancelled" to Dot.IDLE
    }
    StatusLine(text, dot)
    if (r.phase == ReceivePhase.TRANSFERRING && (r.meta?.size ?: 0) > 0) {
        LinearProgressIndicator(progress = { r.bytes.toFloat() / r.meta!!.size }, modifier = Modifier.fillMaxWidth())
    }
    if (r.phase == ReceivePhase.DONE && r.error != null) Note(r.error)

    when (r.phase) {
        ReceivePhase.NEED_DESTINATION -> {
            if (r.approval) Note("The sender will be asked before anything is sent; you may wait a moment after choosing.")
            Button(onClick = { createDoc.launch(r.meta?.name ?: "download") }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.medium) {
                Text("Save to…")
            }
            OutlinedButton(onClick = { ReceiveManager.cancel(r.id) }, modifier = Modifier.fillMaxWidth()) { Text("Decline") }
        }
        ReceivePhase.DONE -> {
            Eyebrow("Done")
            r.destination?.let { uri ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, r.meta?.mime ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            runCatching { context.startActivity(Intent.createChooser(open, "Open with")) }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Open") }
                    if (r.meta?.mime == "application/zip" && r.unpackedTo == null) {
                        OutlinedButton(onClick = { pickTree.launch(null) }, modifier = Modifier.weight(1f)) { Text("Unpack into…") }
                    }
                }
            }
            TextButton(onClick = { ReceiveManager.dismiss(r.id); onDone() }) { Text("Done") }
        }
        ReceivePhase.FAILED, ReceivePhase.CANCELLED -> {
            TextButton(onClick = { ReceiveManager.dismiss(r.id); onDone() }) { Text("Back") }
        }
        else -> {
            OutlinedButton(onClick = { ReceiveManager.cancel(r.id) }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            Text(
                "You can leave the app; the download continues in the background and shows in your notifications.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
