package app.carnet.models

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.carnet.CarnetApp
import app.carnet.MainActivity
import app.carnet.app
import app.carnet.update.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Keeps model and app-update downloads running when the app is in the background or the screen is
 * off, with a notification showing live progress, size and speed. Stops itself when nothing is left.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) { app.models.pauseAll(); app.updater.cancel() }
        val first = text(app.models.status.value, app.updater.state.value)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, ID, notification(first), type)
        if (watcher == null) watcher = scope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            combine(app.models.status, app.updater.state) { m, u -> m to u }.sample(500).collect { (m, u) ->
                val t = text(m, u)
                if (t == null) {
                    // Give a final "ready" notice, then go away.
                    if (seen.any { m[it] is ModelStatus.Installed }) notifyDone()
                    delay(300)
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else getSystemService(NotificationManager::class.java).notify(ID, notification(t))
            }
        }
        return START_NOT_STICKY
    }

    private data class Progress(val title: String, val line: String, val done: Long, val total: Long)

    /** Models downloaded during this run (for the "finished" notice). */
    private val seen = HashSet<String>()

    private fun text(m: Map<String, ModelStatus>, u: UpdateState): Progress? {
        val active = m.filter { (_, s) -> s is ModelStatus.Downloading && !s.paused || s is ModelStatus.Verifying }
        seen.addAll(active.keys)
        if (u is UpdateState.Downloading) {
            return Progress("Downloading Carnet ${u.info.versionName}", "${fmt(u.done)} of ${fmt(u.total)}" + speed(u.speed), u.done, u.total)
        }
        if (active.isEmpty()) return null
        val specs = active.keys.mapNotNull { app.models.specOrNull(it) }
        val done = active.values.sumOf { (it as? ModelStatus.Downloading)?.done ?: 0 }
        val total = specs.sumOf { it.size }
        val sp = active.values.sumOf { (it as? ModelStatus.Downloading)?.speed ?: 0 }
        val waiting = active.values.firstNotNullOfOrNull { (it as? ModelStatus.Downloading)?.waiting }
        val title = if (specs.size == 1) "Downloading ${specs[0].title}" else "Downloading ${specs.size} models"
        val line = if (active.values.any { it is ModelStatus.Verifying }) "Checking files…"
            else waiting ?: ("${fmt(done)} of ${fmt(total)}" + speed(sp))
        return Progress(title, line, done, total)
    }

    private fun speed(bps: Long) = if (bps > 0) " · ${fmt(bps)}/s" else ""

    private fun notification(p: Progress?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("open", "models"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE)
        val pct = if (p != null && p.total > 0) (p.done * 1000 / p.total).toInt() else 0
        return NotificationCompat.Builder(this, CarnetApp.CH_DOWNLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(p?.title ?: "Downloading")
            .setContentText(p?.line ?: "")
            .setProgress(1000, pct, p == null || p.total <= 0)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Pause", pause)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notifyDone() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CarnetApp.CH_DOWNLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Downloads finished")
            .setContentText("Ready to use offline")
            .setAutoCancel(true).setContentIntent(open).build()
        getSystemService(NotificationManager::class.java).notify(ID + 1, n)
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val ID = 4100
        private const val ACTION_PAUSE = "pause"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
        }

        fun fmt(b: Long): String = when {
            b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
            b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
            b >= 1L shl 10 -> "%d KB".format(b shr 10)
            else -> "$b B"
        }
    }
}
