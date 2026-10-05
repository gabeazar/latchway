package app.latchway

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import app.latchway.data.SettingsRepo
import app.latchway.peer.WebRtc
import app.latchway.receive.ReceiveManager
import app.latchway.share.ShareManager

class LatchwayApp : Application() {
    lateinit var settings: SettingsRepo
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsRepo(this)
        WebRtc.init(this)
        createChannels()
        ShareManager.attach(this)
        ReceiveManager.attach(this)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(Channels.SHARE, getString(R.string.notif_channel_share), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.notif_channel_share_desc)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(Channels.RECEIVE, getString(R.string.notif_channel_receive), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.notif_channel_receive_desc)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(Channels.APPROVAL, getString(R.string.notif_channel_approval), NotificationManager.IMPORTANCE_HIGH).apply {
                description = getString(R.string.notif_channel_approval_desc)
            },
        )
    }

    object Channels {
        const val SHARE = "share"
        const val RECEIVE = "receive"
        const val APPROVAL = "approval"
    }

    companion object {
        lateinit var instance: LatchwayApp
            private set
    }
}
