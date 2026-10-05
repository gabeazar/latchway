package app.latchway.receive

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import app.latchway.LatchwayApp
import app.latchway.MainActivity
import app.latchway.R
import app.latchway.util.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample

/** Keeps a download alive with the app in the background. */
class ReceiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, build())
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "latchway:receive").apply { acquire() }
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "latchway:receive").apply { acquire() }
        watcher = ReceiveManager.receives.sample(500).distinctUntilChanged().onEach {
            if (!ReceiveManager.anyActive()) {
                stopSelf()
                return@onEach
            }
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build())
        }.launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) {
            ReceiveManager.receives.value.forEach { ReceiveManager.cancel(it.id) }
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        ReceiveManager.receives.value.forEach { ReceiveManager.cancel(it.id) }
        stopSelf()
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun build(): Notification {
        val active = ReceiveManager.receives.value.filter { with(ReceiveManager) { it.phase.isActive() } }
        val r = active.firstOrNull()
        val title = when {
            r == null -> getString(R.string.app_name)
            r.meta != null -> getString(R.string.notif_receiving, r.meta.name)
            else -> "Connecting to the sender"
        }
        val text = when (r?.phase) {
            ReceivePhase.TRANSFERRING -> {
                val total = r.meta?.size ?: -1
                val pct = if (total > 0) " · ${r.bytes * 100 / total}%" else ""
                Format.bytes(r.bytes) + pct + (Format.eta(r.bytes, total, System.currentTimeMillis() - r.startedAt)?.let { " · $it" } ?: "")
            }
            ReceivePhase.NEED_DESTINATION -> "Choose where to save it"
            ReceivePhase.NEED_PASSWORD -> "This link needs a password"
            ReceivePhase.WAITING_APPROVAL -> "Waiting for the sender to approve"
            ReceivePhase.UNPACKING -> "Unpacking…"
            else -> "Connecting…"
        }
        val b = NotificationCompat.Builder(this, LatchwayApp.Channels.RECEIVE)
            .setSmallIcon(R.drawable.ic_latch)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .addAction(
                0, getString(R.string.action_cancel),
                PendingIntent.getService(
                    this, 2, Intent(this, ReceiveService::class.java).setAction(ACTION_CANCEL_ALL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        if (r?.phase == ReceivePhase.TRANSFERRING && (r.meta?.size ?: -1) > 0) {
            b.setProgress(1000, (r.bytes * 1000 / r.meta!!.size).toInt(), false)
        }
        return b.build()
    }

    companion object {
        const val NOTIFICATION_ID = 20
        const val ACTION_CANCEL_ALL = "app.latchway.CANCEL_RECEIVES"
    }
}
