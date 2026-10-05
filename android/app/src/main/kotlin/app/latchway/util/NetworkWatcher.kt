package app.latchway.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/** Calls back when the default network changes, so hosts re-dial at once. */
object NetworkWatcher {
    private var started = false

    fun start(context: Context, onChange: () -> Unit) {
        if (started) return
        started = true
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(
            req,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onChange()
                override fun onLost(network: Network) = onChange()
            },
        )
    }
}
