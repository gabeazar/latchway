package app.latchway

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.latchway.share.ShareManager
import app.latchway.ui.LatchwayNav
import app.latchway.ui.LatchwayTheme

/** The single activity; every screen is Compose, navigation is in-app. */
class MainActivity : ComponentActivity() {
    /** Intent payloads the navigation graph picks up once it exists. */
    class Incoming {
        var link by mutableStateOf<String?>(null)
        var uris by mutableStateOf<List<Uri>?>(null)
    }

    private val incoming = Incoming()

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            LatchwayTheme {
                LatchwayNav(incoming)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let { incoming.link = it }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val uris = ShareManager.urisFrom(intent)
                if (uris.isNotEmpty()) {
                    // Keep read access beyond this activity instance.
                    uris.forEach { runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
                    incoming.uris = uris
                }
            }
        }
    }
}
