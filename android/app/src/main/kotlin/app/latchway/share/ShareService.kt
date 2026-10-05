// Foreground service (type dataSync) that keeps the process alive while
// anything is being shared, shows one notification summarising the
// shares, and raises a separate high-priority notification for each
// approval request. The actual work lives in ShareManager.
package app.latchway.share

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import app.latchway.LatchwayApp
import app.latchway.MainActivity
import app.latchway.R
import app.latchway.receive.ReceiveManager
import app.latchway.util.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample

/** Starts or stops the foreground services as the managers' state changes. */
object ServiceControl {
    fun refresh(context: Context) {
        val wantShare = ShareManager.anyActive() || ShareManager.approvals.value.isNotEmpty()
        val intent = Intent(context, ShareService::class.java)
        if (wantShare) {
            try {
                context.startForegroundService(intent)
            } catch (_: Exception) {
                // Background start restrictions: the service was already
                // running or the app is visible; either way nothing to do.
            }
        } else {
            context.stopService(intent)
        }
        ReceiveManager.refreshService(context)
    }
}

class ShareService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, build())
        acquireLocks()
        watcher = combine(ShareManager.shares, ShareManager.approvals) { s, a -> s to a }
            .sample(500)
            .distinctUntilChanged()
            .onEach { (shares, approvals) ->
                if (!ShareManager.anyActive() && approvals.isEmpty()) {
                    stopSelf()
                    return@onEach
                }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build())
                syncApprovalNotifications(approvals)
            }
            .launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            ShareManager.stopAll()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 caps dataSync services at six hours a day. Stop
        // cleanly and tell the user instead of being killed.
        ShareManager.stopAll()
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID + 1,
            NotificationCompat.Builder(this, LatchwayApp.Channels.SHARE)
                .setSmallIcon(R.drawable.ic_latch)
                .setContentTitle("Sharing stopped")
                .setContentText("Android limits background sharing to six hours a day. Start the share again to continue.")
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build(),
        )
        stopSelf()
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        releaseLocks()
        clearApprovalNotifications()
        super.onDestroy()
    }

    private fun acquireLocks() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "latchway:share").apply { acquire() }
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "latchway:share").apply { acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun build(): Notification {
        val shares = ShareManager.shares.value.filter { with(ShareManager) { it.phase.isActive() } }
        val transferring = shares.flatMap { s -> s.receivers.values.filter { it.phase == "transferring" }.map { s to it } }
        val title = when (shares.size) {
            0 -> getString(R.string.app_name)
            1 -> getString(R.string.notif_sharing, shares[0].meta.name.ifEmpty { "a file" })
            else -> "Sharing ${shares.size} files"
        }
        val text = when {
            transferring.isNotEmpty() -> {
                val (s, r) = transferring[0]
                val pct = if (s.meta.size > 0) " · ${r.bytes * 100 / s.meta.size}%" else ""
                getString(R.string.notif_sending) + " · " + Format.bytes(r.bytes) + pct +
                    if (transferring.size > 1) " (+${transferring.size - 1} more)" else ""
            }
            shares.any { it.phase == SharePhase.OFFLINE } -> "Reconnecting to the rendezvous…"
            else -> getString(R.string.notif_sharing_waiting)
        }
        val b = NotificationCompat.Builder(this, LatchwayApp.Channels.SHARE)
            .setSmallIcon(R.drawable.ic_latch)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                0, getString(R.string.action_stop),
                PendingIntent.getService(
                    this, 1, Intent(this, ShareService::class.java).setAction(ACTION_STOP_ALL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        if (transferring.isNotEmpty()) {
            val (s, r) = transferring[0]
            if (s.meta.size > 0) b.setProgress(1000, (r.bytes * 1000 / s.meta.size).toInt(), false)
        }
        return b.build()
    }

    private val shownApprovals = HashSet<String>()

    private fun syncApprovalNotifications(approvals: List<PendingApproval>) {
        val nm = getSystemService(NotificationManager::class.java)
        val keys = approvals.map { it.shareId + "/" + it.sid }.toSet()
        for (a in approvals) {
            val key = a.shareId + "/" + a.sid
            if (!shownApprovals.add(key)) continue
            val share = ShareManager.get(a.shareId) ?: continue
            fun action(allow: Boolean) = PendingIntent.getBroadcast(
                this, key.hashCode() * 2 + (if (allow) 1 else 0),
                Intent(this, ApprovalReceiver::class.java).setAction(if (allow) ApprovalReceiver.ALLOW else ApprovalReceiver.DENY)
                    .putExtra("share", a.shareId).putExtra("sid", a.sid),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            nm.notify(
                key.hashCode(),
                NotificationCompat.Builder(this, LatchwayApp.Channels.APPROVAL)
                    .setSmallIcon(R.drawable.ic_latch)
                    .setContentTitle(getString(R.string.notif_approval_title, share.meta.name))
                    .setContentText(getString(R.string.notif_approval_text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(openApp())
                    .addAction(0, getString(R.string.action_allow), action(true))
                    .addAction(0, getString(R.string.action_deny), action(false))
                    .setAutoCancel(true)
                    .build(),
            )
        }
        for (key in shownApprovals.toList()) {
            if (key !in keys) {
                nm.cancel(key.hashCode())
                shownApprovals.remove(key)
            }
        }
    }

    private fun clearApprovalNotifications() {
        val nm = getSystemService(NotificationManager::class.java)
        shownApprovals.forEach { nm.cancel(it.hashCode()) }
        shownApprovals.clear()
    }

    companion object {
        const val NOTIFICATION_ID = 10
        const val ACTION_STOP_ALL = "app.latchway.STOP_ALL"
    }
}

/** Notification buttons for approvals. */
class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val share = intent.getStringExtra("share") ?: return
        val sid = intent.getStringExtra("sid") ?: return
        ShareManager.decide(share, sid, intent.action == ALLOW)
    }

    companion object {
        const val ALLOW = "app.latchway.APPROVE"
        const val DENY = "app.latchway.DENY"
    }
}
