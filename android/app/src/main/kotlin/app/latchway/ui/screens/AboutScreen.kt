package app.latchway.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.latchway.BuildConfig
import app.latchway.ui.LatchMark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
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
            LatchMark(Modifier.size(64.dp))
            Text("Latchway ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Send a big file straight from your phone to a friend's with a link. Encrypted end to end, no accounts, no cloud copy. Made by Gabe Azar, licensed under the GPL-3.0.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "The link carries the key. Everything is encrypted on your phone before it leaves and decrypted only on your friend's. The rendezvous server introduces the two phones and never sees the file, its name or its size.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { open("https://latchway.app/") }) { Text("How it works") }
            TextButton(onClick = { open("https://latchway.app/privacy") }) { Text("Privacy policy") }
            TextButton(onClick = { open("https://github.com/gabeazar/latchway") }) { Text("Source code on GitHub") }
            TextButton(onClick = { open("https://github.com/gabeazar/latchway/blob/main/SECURITY.md") }) { Text("Report a security issue") }
        }
    }
}
