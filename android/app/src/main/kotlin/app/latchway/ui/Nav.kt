package app.latchway.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.latchway.MainActivity
import app.latchway.ui.screens.AboutScreen
import app.latchway.ui.screens.HomeScreen
import app.latchway.ui.screens.ReceiveScreen
import app.latchway.ui.screens.SendOptionsScreen
import app.latchway.ui.screens.SettingsScreen
import app.latchway.ui.screens.ShareScreen

object Routes {
    const val HOME = "home"
    const val SEND = "send"
    const val SHARE = "share/{id}"
    const val RECEIVE = "receive"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    fun share(id: String) = "share/$id"
}

/** Files picked or shared into the app, waiting for the options screen. */
object PendingFiles {
    var uris by mutableStateOf<List<Uri>>(emptyList())
}

/** A link opened from outside, waiting for the receive screen. */
object PendingLink {
    var link by mutableStateOf<String?>(null)
}

@Composable
fun LatchwayNav(incoming: MainActivity.Incoming) {
    val nav: NavHostController = rememberNavController()

    LaunchedEffect(incoming.link) {
        incoming.link?.let {
            PendingLink.link = it
            incoming.link = null
            nav.navigate(Routes.RECEIVE) { launchSingleTop = true }
        }
    }
    LaunchedEffect(incoming.uris) {
        incoming.uris?.let {
            PendingFiles.uris = it
            incoming.uris = null
            nav.navigate(Routes.SEND) { launchSingleTop = true }
        }
    }

    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onPicked = { uris ->
                    PendingFiles.uris = uris
                    nav.navigate(Routes.SEND)
                },
                onReceive = { nav.navigate(Routes.RECEIVE) },
                onOpenShare = { nav.navigate(Routes.share(it)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onAbout = { nav.navigate(Routes.ABOUT) },
            )
        }
        composable(Routes.SEND) {
            SendOptionsScreen(
                uris = PendingFiles.uris,
                onBack = { nav.popBackStack() },
                onStarted = { id ->
                    PendingFiles.uris = emptyList()
                    nav.navigate(Routes.share(id)) { popUpTo(Routes.HOME) }
                },
            )
        }
        composable(Routes.SHARE) { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            ShareScreen(id = id, onBack = { nav.popBackStack() })
        }
        composable(Routes.RECEIVE) {
            ReceiveScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
    }
}
