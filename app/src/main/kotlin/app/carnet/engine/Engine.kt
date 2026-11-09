package app.carnet.engine

import android.content.Context
import android.util.Log
import app.carnet.core.mt.DirectorySource
import app.carnet.core.mt.GlossaryEntry
import app.carnet.core.mt.MarianModel
import app.carnet.core.mt.MarianSentenceTranslator
import app.carnet.core.mt.SentenceTranslator
import app.carnet.core.mt.TextTranslator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The offline FR→EN translator. The bundled model is unpacked from the APK on first launch
 * (so ONNX Runtime can load it from disk), then warmed up so the first real translation is fast.
 */
class Engine(private val context: Context) {
    enum class State { Loading, Ready, Failed }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(State.Loading)
    val state: StateFlow<State> = _state
    private val ready = CompletableDeferred<TextTranslator>()
    private var model: MarianModel? = null
    private val decodeLock = Mutex()
    @Volatile var error: String? = null; private set

    /** Interactive typing always uses beam 1 (fast); documents use [docBeam] (4 = "Careful" quality). */
    @Volatile var beam = 1
    @Volatile var docBeam = 1

    val modelDir: File get() = File(context.filesDir, "models/opus-fr-en")

    fun start(glossary: List<GlossaryEntry>, spelling: String) {
        scope.launch {
            try {
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                val m = MarianModel(AssetSource(context, "models/opus-fr-en"), threads = threads)
                model = m
                val sentence = SentenceTranslator { s, cancel -> m.translate(s, beam = beam, cancel = cancel) }
                val tr = TextTranslator(sentence, glossary)
                tr.spelling = spelling
                // Warm-up: first ONNX Runtime run allocates arenas and JITs kernels.
                tr.translate("Bonjour.")
                ready.complete(tr)
                _state.value = State.Ready
            } catch (t: Throwable) {
                Log.e("Carnet", "engine failed", t)
                error = t.message
                _state.value = State.Failed
                ready.completeExceptionally(t)
            }
        }
    }

    /** Reads the bundled model straight from the APK; .onnx files are stored uncompressed and memory-mapped. */
    private class AssetSource(private val context: Context, private val dir: String) : app.carnet.core.mt.ModelSource {
        override fun open(name: String): ByteArray = context.assets.open("$dir/$name").use { it.readBytes() }
        override fun buffer(name: String): java.nio.ByteBuffer? = if (!name.endsWith(".onnx")) null else runCatching {
            context.assets.openFd("$dir/$name").use { afd ->
                java.io.FileInputStream(afd.fileDescriptor).channel.use { ch -> ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length) }
            }
        }.getOrNull()
    }

    @Suppress("unused")
    private fun unpack() {
        val names = context.assets.list("models/opus-fr-en") ?: emptyArray()
        val stamp = File(modelDir, ".version")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.lastUpdateTime}" }
        if (stamp.exists() && stamp.readText() == version && names.all { File(modelDir, it).exists() }) return
        modelDir.mkdirs()
        for (n in names) {
            val tmp = File(modelDir, "$n.part")
            context.assets.open("models/opus-fr-en/$n").use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
            tmp.renameTo(File(modelDir, n))
        }
        stamp.writeText(version)
    }

    suspend fun translator(): TextTranslator = ready.await()

    /** Translates [texts]; runs serialised so a typing burst never fights a document job for the CPU. */
    suspend fun translate(texts: List<String>, cancel: AtomicBoolean = AtomicBoolean(false), progress: (Int, Int) -> Unit = { _, _ -> }): List<String> {
        val tr = translator()
        return withContext(Dispatchers.Default) {
            decodeLock.withLock { tr.translateAll(texts, { cancel.get() }, progress) }
        }
    }

    suspend fun translate(text: String): String = translate(listOf(text))[0]

    suspend fun setGlossary(g: List<GlossaryEntry>) { translator().setGlossary(g) }
    suspend fun setSpelling(s: String) { translator().spelling = s }

    /** Exclusive access for long jobs (documents) that call the translator many times. */
    suspend fun <T> exclusive(block: suspend (TextTranslator) -> T): T {
        val tr = translator()
        return decodeLock.withLock {
            val old = beam
            beam = docBeam
            try { withContext(Dispatchers.Default) { block(tr) } } finally { beam = old }
        }
    }
}
