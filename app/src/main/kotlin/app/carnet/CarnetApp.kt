package app.carnet

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import app.carnet.data.Library
import app.carnet.data.Settings
import app.carnet.engine.Engine
import app.carnet.jobs.Jobs
import app.carnet.models.ModelManager
import app.carnet.ocr.Scanner
import app.carnet.update.Updater
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Process-wide singletons. Everything is created lazily so app start stays instant. */
class CarnetApp : Application() {
    /** App-wide scope; failures are logged, never fatal. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.e("Carnet", "background task failed", e) })
    val settings by lazy { Settings(this) }
    val engine by lazy { Engine(this) }
    val library by lazy { Library(this) }
    val scanner by lazy { Scanner(this, engine) }
    val jobs by lazy { Jobs(this) }
    val models by lazy { ModelManager(this) }
    val updater by lazy { Updater(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        PDFBoxResourceLoader.init(this)
        createChannels()
        scope.launch {
            val s = settings.flow.first()
            engine.start(s.glossary, s.spelling)
            // Keep the engine in sync with glossary/spelling edits.
            launch { settings.flow.map { it.direction }.distinctUntilChanged().collect { engine.direction = it } }
            // New models and app updates (silent when offline).
            launch { models.refreshCatalog() }
            updater.check()
            settings.flow.map { Triple(it.glossary, it.spelling, it.quality) }.distinctUntilChanged().collect { (g, sp, q) ->
                engine.docBeam = if (q == "careful") 4 else 1
                engine.setGlossary(g); engine.setSpelling(sp)
            }
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_WORK, "Translations", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_RECORD, "Recording", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_SHARE, "Model sharing", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DOWNLOAD, "Downloads", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_UPDATE, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
    }

    companion object {
        const val CH_WORK = "work"
        const val CH_RECORD = "record"
        const val CH_SHARE = "share"
        const val CH_DOWNLOAD = "download"
        const val CH_UPDATE = "update"
        lateinit var instance: CarnetApp
            private set
    }
}

val Context.app: CarnetApp get() = applicationContext as CarnetApp
