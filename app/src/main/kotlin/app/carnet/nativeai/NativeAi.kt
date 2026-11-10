package app.carnet.nativeai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.carnet.app
import app.carnet.core.text.Dictation
import app.carnet.ocr.ScanPage
import app.carnet.record.Recorder
import app.carnet.record.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/** JNI bridge to the bundled native engines (llama.cpp, whisper.cpp). */
object NativeLib {
    /** The engines are built for ARMv8.2 dot-product CPUs (most phones since 2018). */
    private val cpuOk: Boolean by lazy { runCatching { java.io.File("/proc/cpuinfo").readText().contains("asimddp") }.getOrDefault(false) }
    val whisperLoaded: Boolean by lazy { cpuOk && runCatching { System.loadLibrary("carnet_whisper") }.isSuccess }
    val llamaLoaded: Boolean by lazy { cpuOk && runCatching { System.loadLibrary("carnet_llama") }.isSuccess }

    // whisper.cpp
    @JvmStatic external fun whisperInit(model: String, threads: Int): Long
    @JvmStatic external fun whisperTranscribe(ctx: Long, pcm: FloatArray, language: String, cb: ProgressCallback): Array<String>
    @JvmStatic external fun whisperLive(ctx: Long, pcm: FloatArray, language: String, prompt: String): String
    @JvmStatic external fun whisperFree(ctx: Long)

    // llama.cpp + mtmd (TranslateGemma with vision)
    @JvmStatic external fun gemmaInit(model: String, mmproj: String, threads: Int, ctxSize: Int): Long
    @JvmStatic external fun gemmaTranslateText(ctx: Long, prompt: String, maxTokens: Int): String
    @JvmStatic external fun gemmaTranslateImage(ctx: Long, image: ByteArray, prompt: String, maxTokens: Int): String
    @JvmStatic external fun gemmaFree(ctx: Long)
}

fun interface ProgressCallback { fun onProgress(percent: Int) }

private fun threads() = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

/**
 * Whisper "Improve": re-transcribes a recording with a stronger model after class, keeping
 * timestamps, then re-translates each segment.
 */
object Whisper {
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress
    private val lock = Mutex()

    fun improveInBackground(context: Context, id: String) {
        val mm = context.app.models
        val model = listOf("whisper-turbo", "whisper-small").firstOrNull { mm.isInstalled(it) } ?: return
        if (!NativeLib.whisperLoaded) return
        _progress.value = _progress.value + (id to 0f)
        context.app.scope.launch(Dispatchers.Default) {
            lock.withLock {
                runCatching {
                    val dir = context.app.library.dir(id)
                    val pcm = readWav(File(dir, "audio.wav"))
                    val h = NativeLib.whisperInit(mm.file(model, "model.bin").path, threads())
                    require(h != 0L) { "Couldn't load the model" }
                    val out = try {
                        NativeLib.whisperTranscribe(h, pcm, if (File(dir, "lang").let { it.exists() && it.readText().trim() == "en" }) "en" else "fr") { p -> _progress.value = _progress.value + (id to (p / 100f * 0.9f)) }
                    } finally { NativeLib.whisperFree(h) }
                    // Each entry is "startMs\ttext".
                    val punct = context.app.settings.flowValue().spokenPunctuation
                    val old = Recorder.loadTranscript(File(dir, "transcript.json"))
                    val segs = out.mapNotNull { line ->
                        val t = line.substringBefore('\t').toLongOrNull() ?: return@mapNotNull null
                        val text = Dictation.format(line.substringAfter('\t').trim(), punct)
                        if (text.isBlank()) null else Segment(t, text, marked = old.any { it.marked && kotlin.math.abs(it.startMs - t) < 4000 })
                    }
                    val en = context.app.engine.translate(segs.map { it.french })
                    Recorder.saveTranscript(File(dir, "transcript.json"), segs.mapIndexed { i, s -> s.copy(english = en[i]) })
                }
                _progress.value = _progress.value + (id to 1f)
            }
        }
    }

    /** 16-bit mono WAV → floats in [-1, 1]. */
    fun readWav(f: File): FloatArray {
        RandomAccessFile(f, "r").use { r ->
            val n = ((r.length() - 44) / 2).toInt()
            val bytes = ByteArray(n * 2)
            r.seek(44); r.readFully(bytes)
            return FloatArray(n) { i -> ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f }
        }
    }
}

/** TranslateGemma: reads handwriting and hard sentences; loaded on demand, freed after use. */
class Gemma private constructor(private val context: Context) {
    private val lock = Mutex()

    private suspend fun <T> withModel(block: (Long) -> T): T = lock.withLock {
        withContext(Dispatchers.Default) {
            check(NativeLib.llamaLoaded) { "The handwriting engine isn't supported on this phone" }
            val mm = context.app.models
            check(mm.isInstalled("handwriting")) { "Download the handwriting model first" }
            val h = NativeLib.gemmaInit(mm.file("handwriting", "model.gguf").path, mm.file("handwriting", "vision.gguf").path, threads(), 4096)
            check(h != 0L) { "Not enough memory to load the handwriting model" }
            try { block(h) } finally { NativeLib.gemmaFree(h) }
        }
    }

    private fun prompt(text: String?) = "You are a professional French (fr) to English (en) translator. Your goal is to accurately convey the meaning and nuances of the original French text while adhering to English grammar, vocabulary, and cultural sensitivities.\n" +
        (if (text != null) "Produce only the English translation, without any additional explanations or commentary. Please translate the following French text into English:\n\n\n$text"
        else "Please translate the French text in the provided image into English. Produce only the English translation, without any additional explanations, alternatives or commentary. Focus only on the text, do not output where the text is located, surrounding objects or any other explanation about the picture. Ignore symbols, pictogram, and arrows!\n\n\n")

    suspend fun translateText(text: String): String = withModel { h -> NativeLib.gemmaTranslateText(h, prompt(text), 1024).trim() }

    /** Handwritten page: the model reads and translates the photo directly; the English is laid out on a clean page. */
    suspend fun scanPage(bmp: Bitmap, ctx: Context): ScanPage {
        val jpeg = ByteArrayOutputStream().also { Bitmap.createScaledBitmap(bmp, 896, (896f * bmp.height / bmp.width).toInt(), true).compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val english = withModel { h -> NativeLib.gemmaTranslateImage(h, jpeg, prompt(null), 1536).trim() }
        // French text for the Text tab comes from ML Kit (best effort on handwriting).
        val french = runCatching { ctx.app.scanner.lines(bmp).first.joinToString("\n") { it.text } }.getOrDefault("")
        val page = renderPage(english, bmp.width, bmp.height)
        return ScanPage(bmp, page, french, english, 1)
    }

    private fun renderPage(text: String, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawColor(android.graphics.Color.WHITE)
        val tp = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            typeface = android.graphics.Typeface.createFromAsset(context.assets, "fonts/LiberationSans-Regular.ttf")
        }
        val margin = w / 14
        var size = w / 26f
        var layout: android.text.StaticLayout
        do {
            tp.textSize = size
            layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length, tp, w - 2 * margin).setLineSpacing(0f, 1.25f).build()
            size *= 0.92f
        } while (layout.height > h - 2 * margin && size > 10)
        c.translate(margin.toFloat(), margin.toFloat()); layout.draw(c)
        return out
    }

    companion object {
        @Volatile private var inst: Gemma? = null
        fun get(c: Context) = inst ?: synchronized(this) { inst ?: Gemma(c.applicationContext).also { inst = it } }
    }
}

private suspend fun app.carnet.data.Settings.flowValue() = flow.first()
