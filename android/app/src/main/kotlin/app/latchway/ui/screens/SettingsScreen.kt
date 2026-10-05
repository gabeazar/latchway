package app.latchway.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.latchway.LatchwayApp
import app.latchway.data.Settings
import app.latchway.ui.Eyebrow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val repo = LatchwayApp.instance.settings
    val s by repo.flow.collectAsState(initial = Settings())
    val scope = rememberCoroutineScope()
    fun set(fn: (Settings) -> Settings) = scope.launch { repo.update(fn) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            Eyebrow("You")
            OutlinedTextField(
                value = s.displayName, onValueChange = { v -> set { it.copy(displayName = v) } },
                label = { Text("Name shown to receivers (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            Eyebrow("Defaults for new links")
            ToggleRow("Ask me before each download", "Every receiver waits for your approval.", s.askApproval) { v -> set { it.copy(askApproval = v) } }
            ToggleRow("Relay only", "Never reveal this phone's address. Slower, and it uses the relay allowance.", s.relayOnly) { v -> set { it.copy(relayOnly = v) } }
            Text("Downloads", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "Once", 3 to "3 times", 0 to "No limit").forEach { (n, label) ->
                    FilterChip(selected = s.defaultDownloads == n, onClick = { set { it.copy(defaultDownloads = n) } }, label = { Text(label) })
                }
            }
            Text("Link expires", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "1 hour", 24 to "1 day", 168 to "1 week", 0 to "Never").forEach { (h, label) ->
                    FilterChip(selected = s.defaultExpiryHours == h, onClick = { set { it.copy(defaultExpiryHours = h) } }, label = { Text(label) })
                }
            }

            Eyebrow("Server")
            OutlinedTextField(
                value = s.rendezvous, onValueChange = { v -> set { it.copy(rendezvous = v) } },
                label = { Text("Rendezvous") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Where links you create point. latchway.app by default; use your own if you run one. Receivers need no setting.") },
            )
        }
    }
}
