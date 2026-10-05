package app.latchway.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.latchway.LatchwayApp
import app.latchway.share.ShareManager
import app.latchway.share.ShareRequest
import app.latchway.ui.Card
import app.latchway.ui.Eyebrow
import app.latchway.ui.Note
import app.latchway.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class Picked(val name: String, val size: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendOptionsScreen(uris: List<Uri>, onBack: () -> Unit, onStarted: (String) -> Unit) {
    val context = LocalContext.current
    val settingsRepo = LatchwayApp.instance.settings
    var files by remember { mutableStateOf<List<Picked>>(emptyList()) }
    var password by remember { mutableStateOf("") }
    var usePassword by remember { mutableStateOf(false) }
    var approve by remember { mutableStateOf(false) }
    var downloads by remember { mutableStateOf(1) }
    var expiryHours by remember { mutableStateOf(24) }
    var relayOnly by remember { mutableStateOf(false) }
    var from by remember { mutableStateOf("") }
    var rendezvous by remember { mutableStateOf("latchway.app") }
    var loaded by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uris) {
        val s = settingsRepo.current()
        approve = s.askApproval
        downloads = s.defaultDownloads
        expiryHours = s.defaultExpiryHours
        relayOnly = s.relayOnly
        from = s.displayName
        rendezvous = s.rendezvous
        val result = withContext(Dispatchers.IO) {
            runCatching {
                uris.map { uri ->
                    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                    var size = -1L
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { i -> c.getString(i)?.let { name = it } }
                            c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { i -> if (!c.isNull(i)) size = c.getLong(i) }
                        }
                    }
                    if (size < 0) context.contentResolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
                    Picked(name, size)
                }
            }
        }
        result.onSuccess { files = it }.onFailure { e ->
            problem = when (e) {
                is SecurityException -> "Latchway wasn't given access to this file. Pick it again from inside the app."
                else -> "Can't read this file: ${e.message ?: e::class.simpleName}"
            }
        }
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Send") },
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
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!loaded) {
                        Text("Reading…", style = MaterialTheme.typography.bodyMedium)
                    } else if (problem != null) {
                        Text(problem!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    } else if (files.size == 1) {
                        Text(files[0].name, style = MaterialTheme.typography.titleMedium)
                        Text(Format.bytes(files[0].size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("${files.size} files, sent as one zip", style = MaterialTheme.typography.titleMedium)
                        files.take(6).forEach { Text("• ${it.name}  ·  ${Format.bytes(it.size)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (files.size > 6) Text("…and ${files.size - 6} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Total " + Format.bytes(files.sumOf { it.size.coerceAtLeast(0) }), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Eyebrow("Who can get it")
            ToggleRow("Password on the link", "The receiver must type it. It never reaches any server.", usePassword) { usePassword = it }
            if (usePassword) {
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, label = { Text("Password") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
            }
            ToggleRow("Ask me before each download", "You'll get a prompt when someone opens the link.", approve) { approve = it }

            Eyebrow("How long")
            Text("Downloads", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "Once", 3 to "3 times", 0 to "No limit").forEach { (n, label) ->
                    FilterChip(selected = downloads == n, onClick = { downloads = n }, label = { Text(label) })
                }
            }
            Text("Link expires", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "1 hour", 24 to "1 day", 168 to "1 week", 0 to "Never").forEach { (h, label) ->
                    FilterChip(selected = expiryHours == h, onClick = { expiryHours = h }, label = { Text(label) })
                }
            }

            Eyebrow("Privacy")
            ToggleRow("Relay only", "Never reveal this phone's address to the receiver. Slower, and uses the relay allowance.", relayOnly) { relayOnly = it }
            OutlinedTextField(value = from, onValueChange = { from = it }, label = { Text("Your name, shown to the receiver (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            if (rendezvous != "latchway.app") Note("Using your own rendezvous: $rendezvous")

            Button(
                onClick = {
                    val id = ShareManager.start(
                        ShareRequest(
                            uris = uris, password = if (usePassword) password else "", askApproval = approve,
                            maxDownloads = downloads, expiresInHours = expiryHours, relayOnly = relayOnly, from = from, rendezvous = rendezvous,
                        ),
                    )
                    onStarted(id)
                },
                enabled = loaded && problem == null && files.isNotEmpty() && (!usePassword || password.isNotEmpty()),
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.medium,
            ) { Text("Create the link") }
            Text(
                "Your phone keeps the file. Latchway must stay installed and this share active until your friend has it; you can close the app.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
