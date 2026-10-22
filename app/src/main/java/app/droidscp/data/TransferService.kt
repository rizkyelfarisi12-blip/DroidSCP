package app.droidscp.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.droidscp.DroidScpApp
import app.droidscp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Menjaga proses tetap hidup selama ada transfer aktif; berhenti sendiri saat antrean kosong. */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startInForeground(buildNotification(0, 0))

        if (watcher == null) {
            val manager = (application as DroidScpApp).transfers
            watcher = scope.launch {
                manager.jobs.collect { jobs ->
                    val active = jobs.filter { it.isActive }
                    if (active.isEmpty()) {
                        stopForegroundCompat()
                        stopSelf()
                    } else {
                        val known = active.filter { it.total > 0 }
                        val pct = if (known.isEmpty()) 0 else {
                            (known.sumOf { it.done } * 100 / known.sumOf { it.total }).toInt().coerceIn(0, 100)
                        }
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIF_ID, buildNotification(active.size, pct))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(n: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    private fun stopForegroundCompat() = ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)

    private fun buildNotification(count: Int, percent: Int): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("DroidSCP")
            .setContentText(if (count == 0) "Menyiapkan transfer…" else "$count transfer berjalan")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (count > 0 && percent > 0) builder.setProgress(100, percent, false)
        else builder.setProgress(0, 0, true)
        return builder.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Transfer berkas", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "transfers"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
        }
    }
}
