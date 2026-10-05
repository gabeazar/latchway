package app.latchway.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.latchway.receive.ActiveReceive
import app.latchway.receive.ReceiveManager
import app.latchway.receive.ReceivePhase
import app.latchway.share.ActiveShare
import app.latchway.share.ShareManager
import app.latchway.share.SharePhase
import app.latchway.ui.Card
import app.latchway.ui.Dot
import app.latchway.ui.Eyebrow
import app.latchway.ui.LatchMark
import app.latchway.ui.StatusLine
import app.latchway.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onPicked: (List<Uri>) -> Unit,
    onReceive: () -> Unit,
    onOpenShare: (String) -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    val shares by ShareManager.shares.collectAsState()
    val receives by ReceiveManager.receives.collectAsState()
    val approvals by ShareManager.approvals.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LatchMark(Modifier.width(30.dp).height(30.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Latchway", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings") }
                    IconButton(onClick = onAbout) { Icon(Icons.Outlined.Info, "About") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Send a big file straight to a friend's phone.", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Pick a file, get a link, send the link however you like. The file crosses directly between your phones, encrypted the whole way.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.medium) {
                Text("Send a file")
            }
            OutlinedButton(onClick = onReceive, modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.medium) {
                Text("Receive with a link")
            }

            if (approvals.isNotEmpty()) {
                Eyebrow("Waiting for you")
                approvals.forEach { a ->
                    val share = shares.firstOrNull { it.id == a.shareId } ?: return@forEach
                    Card {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Someone wants ${share.meta.name}", style = MaterialTheme.typography.titleMedium)
                            Text("Send it to them?", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { ShareManager.decide(a.shareId, a.sid, true) }) { Text("Send") }
                                OutlinedButton(onClick = { ShareManager.decide(a.shareId, a.sid, false) }) { Text("Don't send") }
                            }
                        }
                    }
                }
            }

            val activeShares = shares.filter { with(ShareManager) { it.phase.isActive() } }
            val pastShares = shares.filterNot { with(ShareManager) { it.phase.isActive() } }
            if (activeShares.isNotEmpty()) {
                Eyebrow("Sharing now")
                activeShares.forEach { ShareRow(it) { onOpenShare(it.id) } }
            }
            if (receives.isNotEmpty()) {
                Eyebrow("Receiving")
                receives.forEach { ReceiveRow(it, onReceive) }
            }
            if (pastShares.isNotEmpty()) {
                Eyebrow("Earlier")
                pastShares.forEach { ShareRow(it) { onOpenShare(it.id) } }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ShareRow(s: ActiveShare, onClick: () -> Unit) {
    Card(Modifier.clickable(onClick = onClick)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.meta.name.ifEmpty { "Preparing…" }, style = MaterialTheme.typography.titleMedium)
            val transferring = s.receivers.values.filter { it.phase == "transferring" }
            val (text, dot) = when (s.phase) {
                SharePhase.STARTING -> "Starting…" to Dot.BUSY
                SharePhase.WAITING -> "Waiting for someone to open the link" to Dot.GOOD
                SharePhase.OFFLINE -> "Reconnecting…" to Dot.BAD
                SharePhase.TRANSFERRING -> "Sending to ${transferring.size} receiver${if (transferring.size == 1) "" else "s"}" to Dot.BUSY
                SharePhase.FINISHED -> "Done: ${s.completed} download${if (s.completed == 1) "" else "s"}" to Dot.IDLE
                SharePhase.EXPIRED -> "Expired after ${s.completed} download${if (s.completed == 1) "" else "s"}" to Dot.IDLE
                SharePhase.STOPPED -> "Stopped" to Dot.IDLE
                SharePhase.FAILED -> (s.error ?: "Failed") to Dot.BAD
            }
            StatusLine(text, dot)
            transferring.firstOrNull()?.let { r ->
                if (s.meta.size > 0) LinearProgressIndicator(progress = { r.bytes.toFloat() / s.meta.size }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ReceiveRow(r: ActiveReceive, onClick: () -> Unit) {
    Card(Modifier.clickable(onClick = onClick)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(r.meta?.name ?: "Connecting…", style = MaterialTheme.typography.titleMedium)
            val (text, dot) = when (r.phase) {
                ReceivePhase.CONNECTING -> "Connecting to the sender" to Dot.BUSY
                ReceivePhase.NEED_PASSWORD -> "Needs the password" to Dot.BUSY
                ReceivePhase.NEED_DESTINATION -> "Choose where to save it" to Dot.BUSY
                ReceivePhase.WAITING_APPROVAL -> "Waiting for the sender to approve" to Dot.BUSY
                ReceivePhase.CONNECTING_PEER -> "Connecting…" to Dot.BUSY
                ReceivePhase.TRANSFERRING -> (Format.bytes(r.bytes) + (r.meta?.size?.takeIf { it > 0 }?.let { " of " + Format.bytes(it) } ?: "")) to Dot.BUSY
                ReceivePhase.UNPACKING -> "Unpacking…" to Dot.BUSY
                ReceivePhase.DONE -> "Saved" to Dot.IDLE
                ReceivePhase.FAILED -> (r.error ?: "Failed") to Dot.BAD
                ReceivePhase.CANCELLED -> "Cancelled" to Dot.IDLE
            }
            StatusLine(text, dot)
            if (r.phase == ReceivePhase.TRANSFERRING && (r.meta?.size ?: 0) > 0) {
                LinearProgressIndicator(progress = { r.bytes.toFloat() / r.meta!!.size }, modifier = Modifier.fillMaxWidth())
            }
            if (!with(ReceiveManager) { r.phase.isActive() }) {
                TextButton(onClick = { ReceiveManager.dismiss(r.id) }) { Text("Dismiss") }
            }
        }
    }
}
