package app.carnet.diag

import android.content.Context
import app.carnet.BuildConfig
import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.SpanStatus
import io.sentry.android.core.SentryAndroid
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException

/**
 * Crash and performance reporting (Sentry) plus the one place that turns exceptions into words a
 * student can act on. Nothing the user types, scans, records or opens is ever sent: only what went
 * wrong, where, how long things took, and the phone model.
 */
object Diag {
    private const val DSN = "https://48cf6a1015944eeac5b7c3778c19013a@o4512164490444800.ingest.de.sentry.io/4512403600769104"
    @Volatile private var on = false

    fun start(context: Context, enabled: Boolean) {
        // Unit tests and local dev builds don't report.
        if (!enabled || BuildConfig.VERSION_NAME.endsWith("-dev") || isRobolectric()) return
        runCatching {
            SentryAndroid.init(context) { o ->
                o.dsn = DSN
                o.release = "carnet@${BuildConfig.VERSION_NAME}"
                o.environment = if (BuildConfig.DEBUG) "debug" else "release"
                o.tracesSampleRate = 1.0
                o.isSendDefaultPii = false
                o.isAttachScreenshot = false
                o.isAttachViewHierarchy = false
                o.isEnableUserInteractionBreadcrumbs = true
                o.isAnrEnabled = true
                o.isEnableAppStartProfiling = false
                o.setBeforeBreadcrumb { b, _ -> if (b.category == "ui.input" || b.type == "user") null else b }
            }
            on = true
        }
    }

    private fun isRobolectric() = android.os.Build.FINGERPRINT == "robolectric"

    /** Records a handled problem. */
    fun report(e: Throwable, where: String, extra: Map<String, String> = emptyMap()) {
        if (!on || e is CancellationException || e is InterruptedException) return
        runCatching {
            Sentry.withScope { s ->
                s.setTag("where", where)
                extra.forEach { (k, v) -> s.setExtra(k, v) }
                s.level = SentryLevel.ERROR
                Sentry.captureException(e)
            }
        }
    }

    fun breadcrumb(message: String) { if (on) runCatching { Sentry.addBreadcrumb(message) } }

    /** A timed operation ("translate.document", "ocr.page", "model.load"…) with numbers attached. */
    class Timing internal constructor(private val tx: ITransaction?) {
        private val t0 = System.nanoTime()
        fun tag(k: String, v: String) = apply { tx?.setTag(k, v) }
        fun measure(k: String, v: Number) = apply { tx?.setMeasurement(k, v) }
        fun <T> span(op: String, block: () -> T): T {
            val s = tx?.startChild(op)
            try { return block().also { s?.finish(SpanStatus.OK) } } catch (e: Throwable) { s?.finish(SpanStatus.INTERNAL_ERROR); throw e }
        }
        fun ok() { tx?.setMeasurement("duration_ms", (System.nanoTime() - t0) / 1_000_000); tx?.finish(SpanStatus.OK) }
        fun fail(e: Throwable) { tx?.throwable = e; tx?.finish(if (e is CancellationException || e is InterruptedException) SpanStatus.CANCELLED else SpanStatus.INTERNAL_ERROR) }
    }

    fun timing(name: String, op: String = "task"): Timing = Timing(if (on) runCatching { Sentry.startTransaction(name, op) }.getOrNull() else null)

    /** Runs [block] as a timed operation; failures are reported and rethrown. */
    inline fun <T> timed(name: String, where: String = name, block: (Timing) -> T): T {
        val t = timing(name)
        try { return block(t).also { t.ok() } } catch (e: Throwable) { t.fail(e); report(e, where); throw e }
    }

    /** What to tell the user. Never a class name, stack trace or library message. */
    fun friendly(e: Throwable, doing: String = "do that"): String {
        val m = (e.message ?: "").lowercase()
        return when {
            e is app.carnet.engine.Engine.NoModelException -> e.message!!
            e is CancellationException || e is InterruptedException -> "Cancelled"
            e is OutOfMemoryError || m.contains("out of memory") || m.contains("failed to allocate") -> "This is too large for this phone's memory. Try a smaller file or close other apps."
            e is UnknownHostException || m.contains("unable to resolve host") -> "No internet connection"
            e is SocketTimeoutException || m.contains("timeout") || m.contains("timed out") -> "The connection is too slow. Try again."
            m.contains("enospc") || m.contains("no space left") -> "Your phone's storage is full. Free some space and try again."
            m.contains("password") || m.contains("encrypt") -> "This file is password-protected"
            m.contains("end-of-file") || m.contains("versioninfo") || m.contains("end header") || m.contains("not a zip") || m.contains("empty or damaged") ||
                m.contains("zip") && m.contains("error") || m.contains("unexpected end") -> "This file is empty or damaged"
            m.contains("isn't supported") || m.contains("unsupported file") -> "This kind of file isn't supported yet"
            e is FileNotFoundException || e is SecurityException || m.contains("permission denial") -> "Carnet can't open this file. Pick it again."
            m.contains("damaged in transfer") || m.contains("checksum") -> "The download was damaged. Try again."
            m.contains("server answered") || e is IOException && (m.contains("connection") || m.contains("reset") || m.contains("refused") || m.contains("ssl")) -> "Connection problem. Check your internet and try again."
            m.contains("can't read this image") -> "Carnet can't read this picture"
            else -> "Couldn't $doing. Try again."
        }
    }
}
