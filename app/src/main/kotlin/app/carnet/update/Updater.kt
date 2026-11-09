package app.carnet.update

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import app.carnet.BuildConfig
import app.carnet.CarnetApp
import app.carnet.MainActivity
import app.carnet.app
import app.carnet.models.DownloadService
import app.carnet.models.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String, val size: Long, val sha256: String, val notes: String)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    object UpToDate : UpdateState()
    data class Available(val info: UpdateInfo) : UpdateState()
    data class Downloading(val info: UpdateInfo, val done: Long, val total: Long, val speed: Long) : UpdateState()
    data class Ready(val info: UpdateInfo, val file: File) : UpdateState()
    data class Failed(val message: String) : UpdateState()
}

/**
 * App updates without the Play Store: each CI build is published as a GitHub release with an
 * update.json. The app checks it (on start, at most every few hours), notifies, downloads the new APK
 * in the background with progress, checks its SHA-256 and hands it to Android's package installer.
 * Models live outside the APK, so updates stay small and nothing has to be downloaded again.
 */
class Updater(private val context: Context) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private var job: Job? = null
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val dir = File(context.cacheDir, "updates")

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Checks unless checked recently; [force] from the "Check for updates" button. */
    fun check(force: Boolean = false, notify: Boolean = true) {
        if (job?.isActive == true) return
        val last = prefs.getLong("lastCheck", 0)
        if (!force && System.currentTimeMillis() - last < 4 * 3600_000L) {
            // Still show a known pending update after a restart.
            prefs.getString("pending", null)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
                ?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }?.let { _state.value = UpdateState.Available(it) }
            return
        }
        job = context.app.scope.launch(Dispatchers.IO) {
            if (force) _state.value = UpdateState.Checking
            try {
                val info = parse(JSONObject(httpText(UPDATE_URL)))
                prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                if (info.versionCode > BuildConfig.VERSION_CODE) {
                    prefs.edit().putString("pending", JSONObject().put("versionCode", info.versionCode).put("versionName", info.versionName)
                        .put("apk", info.apkUrl).put("size", info.size).put("sha256", info.sha256).put("notes", info.notes).toString()).apply()
                    _state.value = UpdateState.Available(info)
                    if (notify && prefs.getInt("notified", 0) != info.versionCode) {
                        prefs.edit().putInt("notified", info.versionCode).apply()
                        notifyAvailable(info)
                    }
                } else {
                    prefs.edit().remove("pending").apply()
                    _state.value = if (force) UpdateState.UpToDate else UpdateState.Idle
                }
            } catch (e: Exception) {
                _state.value = if (force) UpdateState.Failed("Couldn't check for updates — are you online?") else UpdateState.Idle
            }
        }
    }

    private fun parse(o: JSONObject) = UpdateInfo(o.getInt("versionCode"), o.getString("versionName"), o.getString("apk"),
        o.getLong("size"), o.getString("sha256"), o.optString("notes"))

    /** Downloads the update in the background (resumable), then opens the installer. */
    fun download(info: UpdateInfo) {
        if (job?.isActive == true && _state.value is UpdateState.Downloading) return
        dir.mkdirs()
        val apk = File(dir, "carnet-${info.versionCode}.apk")
        val part = File(dir, "carnet-${info.versionCode}.apk.part")
        dir.listFiles()?.filter { it.name != apk.name && it.name != part.name }?.forEach { it.delete() }
        _state.value = UpdateState.Downloading(info, part.length(), info.size, 0)
        DownloadService.start(context)
        job = context.app.scope.launch(Dispatchers.IO) {
            try {
                if (!(apk.exists() && apk.length() == info.size)) {
                    var attempt = 0
                    while (true) {
                        try { fetch(info, part); break } catch (e: IOException) {
                            if (++attempt > 6 || !isActive) throw e
                            delay(2000L shl attempt)
                        }
                    }
                    if (ModelManager.sha256(part) != info.sha256) { part.delete(); throw IOException("The download was damaged — try again") }
                    part.renameTo(apk)
                }
                _state.value = UpdateState.Ready(info, apk)
                install(apk)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = UpdateState.Available(info)
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.message ?: "Update failed")
            }
        }
    }

    fun cancel() { if (_state.value is UpdateState.Downloading) job?.cancel() }

    fun reportFailure(message: String) { _state.value = UpdateState.Failed(message) }

    /** Android asks once for permission to install updates from Carnet. */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun install(apk: File) {
        if (!canInstall()) { context.startActivity(installPermissionIntent()); return }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("carnet.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out, 1 shl 16) }; s.fsync(out) }
            val pi = PendingIntent.getBroadcast(context, id, Intent(context, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
            s.commit(pi.intentSender)
        }
    }

    private fun notifyAvailable(info: UpdateInfo) {
        val open = PendingIntent.getActivity(context, 7, Intent(context, MainActivity::class.java).putExtra("open", "update"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CarnetApp.CH_UPDATE)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Carnet ${info.versionName} is available")
            .setContentText(info.notes.ifBlank { "Tap to update (${DownloadService.fmt(info.size)})" })
            .setStyle(NotificationCompat.BigTextStyle().bigText((info.notes.ifBlank { "" } + "\n" + "Tap to update · ${DownloadService.fmt(info.size)}").trim()))
            .setAutoCancel(true).setContentIntent(open).build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(7100, n) }
    }

    private suspend fun fetch(info: UpdateInfo, part: File) = withContext(Dispatchers.IO) {
        var u = info.apkUrl
        var redirects = 0
        while (true) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 15000; c.readTimeout = 30000
            val have = if (part.exists()) part.length() else 0L
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code in 300..399 && redirects++ < 8) { u = URL(URL(u), c.getHeaderField("Location")).toString(); c.disconnect(); continue }
            if (code == 416) return@withContext
            if (code != 200 && code != 206) throw IOException("Server answered $code")
            val append = code == 206
            var done = if (append) have else 0L
            var window = System.currentTimeMillis(); var windowBytes = 0L; var speed = 0L
            c.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); done += n; windowBytes += n
                        val now = System.currentTimeMillis()
                        if (now - window >= 500) {
                            speed = windowBytes * 1000 / (now - window); window = now; windowBytes = 0
                            _state.value = UpdateState.Downloading(info, done, info.size, speed)
                        }
                    }
                }
            }
            if (!isActive) throw kotlinx.coroutines.CancellationException()
            return@withContext
        }
    }

    private fun httpText(url: String): String {
        var u = url
        repeat(8) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 15000; c.readTimeout = 20000
            val code = c.responseCode
            if (code in 300..399) { u = URL(URL(u), c.getHeaderField("Location")).toString(); c.disconnect(); return@repeat }
            if (code != 200) throw IOException("Server answered $code")
            return c.inputStream.use { String(it.readBytes()) }
        }
        throw IOException("Too many redirects")
    }

    companion object {
        const val UPDATE_URL = "https://github.com/openera-co/deepl/releases/latest/download/update.json"
    }
}

/** Receives the installer's result; asks the user to confirm when Android requires it. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT))
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed"
                runCatching { (context.applicationContext as CarnetApp).updater.reportFailure(msg) }
            }
        }
    }
}
