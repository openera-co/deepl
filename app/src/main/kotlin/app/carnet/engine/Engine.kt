package app.carnet.engine

import android.content.Context
import android.util.Log
import app.carnet.app
import app.carnet.core.mt.DirectorySource
import app.carnet.core.mt.GlossaryEntry
import app.carnet.core.mt.MarianModel
import app.carnet.core.mt.SentenceTranslator
import app.carnet.core.mt.TextTranslator
import app.carnet.core.text.LanguageGuess
import app.carnet.models.ModelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The offline translator, both ways (French ↔ English). Models are downloaded files (Models screen),
 * loaded on first use per direction and kept warm. "auto" picks the direction from the text itself.
 */
class Engine(private val context: Context) {
    enum class State { NoModel, Loading, Ready, Failed }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _states = MutableStateFlow(mapOf(FR_EN to State.NoModel, EN_FR to State.NoModel))
    /** Per direction ("fr-en", "en-fr"). */
    val states: StateFlow<Map<String, State>> = _states
    private val _state = MutableStateFlow(State.NoModel)
    /** Overall: Ready when at least one direction works. */
    val state: StateFlow<State> = _state
    @Volatile var error: String? = null; private set

    private class Loaded(val modelId: String, val model: java.io.Closeable, val translator: TextTranslator, val generation: Int)
    private val loaded = HashMap<String, Loaded>()
    private val loadLock = Mutex()
    private val decodeLock = Mutex()

    /** Interactive typing always uses beam 1 (fast); documents use [docBeam] (4 = "Careful" quality). */
    @Volatile var beam = 1
    @Volatile var docBeam = 1
    @Volatile var preferFast = false
    /** "auto", "fr-en" or "en-fr". */
    @Volatile var direction = "auto"
    private var glossary: List<GlossaryEntry> = emptyList()
    private var spelling = "UK"

    /** Switches between the best and the fast models (reloads what's in memory). */
    suspend fun setQuality(fast: Boolean) {
        if (preferFast == fast) return
        preferFast = fast
        loadLock.withLock { loaded.values.forEach { it.model.close() }; loaded.clear() }
        refreshStates()
        for (dir in listOf(FR_EN, EN_FR)) if (context.app.models.translationModel(dir, preferFast) != null) scope.launch { runCatching { translator(dir) } }
    }

    fun start(glossary: List<GlossaryEntry>, spelling: String) {
        this.glossary = glossary; this.spelling = spelling
        refreshStates()
        // Load (or reload) a direction as soon as its model is installed, updated or removed.
        scope.launch {
            kotlinx.coroutines.flow.combine(context.app.models.status.map { st -> st.filterValues { it is ModelStatus.Installed }.keys }, context.app.models.generation) { k, g -> k to g }.distinctUntilChanged().collect {
                loadLock.withLock {
                    for (dir in listOf(FR_EN, EN_FR)) {
                        val want = context.app.models.translationModel(dir, preferFast)?.id
                        if (loaded[dir]?.modelId != want || loaded[dir]?.generation != it.second) loaded.remove(dir)?.model?.close()
                    }
                }
                refreshStates()
                for (dir in listOf(FR_EN, EN_FR)) if (context.app.models.translationModel(dir, preferFast) != null) scope.launch { runCatching { translator(dir) } }
            }
        }
    }

    private fun refreshStates() {
        val m = HashMap(_states.value)
        for (dir in listOf(FR_EN, EN_FR)) {
            m[dir] = when {
                loaded.containsKey(dir) -> State.Ready
                context.app.models.translationModel(dir, preferFast) == null -> State.NoModel
                m[dir] == State.Failed -> State.Failed
                else -> State.Loading
            }
        }
        _states.value = m
        _state.value = when {
            m.values.any { it == State.Ready } -> State.Ready
            m.values.any { it == State.Loading } -> State.Loading
            m.values.any { it == State.Failed } -> State.Failed
            else -> State.NoModel
        }
    }

    /** The direction to use for [sample] text under the current setting. */
    fun resolve(sample: String? = null, override: String? = null): String {
        val d = override ?: direction
        if (d == FR_EN || d == EN_FR) return d
        return when (sample?.let { LanguageGuess.guess(it) }) {
            "en" -> EN_FR
            "fr" -> FR_EN
            else -> if (_states.value[FR_EN] == State.Ready || _states.value[EN_FR] != State.Ready) FR_EN else EN_FR
        }
    }

    /** CTranslate2 threads (passive OpenMP waiting, see native-ct2): 4 on ARM64 gave 14.3 lines/s vs 6.9 for ONNX Runtime. */
    private fun ct2Threads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** The translator for a direction, loading its model if needed. */
    suspend fun translator(dir: String = FR_EN): TextTranslator {
        loaded[dir]?.let { return it.translator }
        return loadLock.withLock {
            loaded[dir]?.let { return@withLock it.translator }
            val spec = context.app.models.translationModel(dir, preferFast)
                ?: throw NoModelException(dir)
            _states.value = _states.value + (dir to State.Loading); refreshStates()
            try {
                withContext(Dispatchers.Default) {
                    val timing = app.carnet.diag.Diag.timing("model.load").tag("model", spec.id)
                    val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                    val mdir = context.app.models.dir(spec.id)
                    // CTranslate2 (C++, int8 NEON kernels) when the model has its files; ONNX Runtime otherwise.
                    val native = if (app.carnet.nativeai.Ct2Model.available(mdir))
                        runCatching { app.carnet.nativeai.Ct2Model(mdir, DirectorySource(mdir), ct2Threads()) }
                            .onFailure { app.carnet.diag.Diag.report(it, "model.load.ct2", mapOf("model" to spec.id)) }.getOrNull() else null
                    timing.tag("engine", if (native != null) "ct2" else "ort")
                    val m: java.io.Closeable
                    val sentence: SentenceTranslator
                    if (native != null) {
                        m = native
                        sentence = object : SentenceTranslator {
                            override fun translateSentences(sentences: List<String>, cancel: () -> Boolean) = native.translator(beam).translateSentences(sentences, cancel)
                            override fun translateCarefully(sentences: List<String>, cancel: () -> Boolean) = native.translator(4).translateSentences(sentences, cancel)
                        }
                    } else {
                        val om = MarianModel(DirectorySource(mdir), threads = threads)
                        m = om
                        sentence = object : SentenceTranslator {
                            override fun translateSentences(sentences: List<String>, cancel: () -> Boolean) = om.translate(sentences, beam = beam, cancel = cancel)
                            override fun translateCarefully(sentences: List<String>, cancel: () -> Boolean) = om.translate(sentences, beam = 4, cancel = cancel)
                        }
                    }
                    val src = dir.substringBefore('-'); val tgt = dir.substringAfter('-')
                    // The glossary is written French → English; reversed for English → French.
                    val g = if (src == "fr") glossary else glossary.map { GlossaryEntry(it.target, it.source) }
                    val tr = TextTranslator(sentence, g, source = src, target = tgt)
                    if (tgt == "en") tr.spelling = spelling
                    // Warm-up: the first ONNX Runtime run allocates arenas.
                    tr.translate(if (src == "fr") "Bonjour." else "Hello.")
                    loaded[dir] = Loaded(spec.id, m, tr, context.app.models.generation.value)
                    timing.ok()
                    error = null
                    tr
                }
            } catch (t: Throwable) {
                Log.e("Carnet", "engine $dir failed", t)
                app.carnet.diag.Diag.report(t, "model.load", mapOf("direction" to dir, "model" to spec.id))
                error = t.message
                _states.value = _states.value + (dir to State.Failed)
                throw t
            } finally { refreshStates() }
        }
    }

    /** Translates [texts] in [dir] (null = by setting / detected). Serialised so typing never fights a document job. */
    suspend fun translate(texts: List<String>, cancel: AtomicBoolean = AtomicBoolean(false), dir: String? = null, progress: (Int, Int) -> Unit = { _, _ -> }): List<String> {
        val d = resolve(texts.joinToString(" ").take(4000), dir)
        val tr = translator(d)
        return withContext(Dispatchers.Default) {
            decodeLock.withLock { tr.translateAll(texts, { cancel.get() }, progress) }
        }
    }

    suspend fun translate(text: String, dir: String? = null): String = translate(listOf(text), dir = dir)[0]

    suspend fun setGlossary(g: List<GlossaryEntry>) {
        glossary = g
        for ((d, l) in loaded) l.translator.setGlossary(if (d == FR_EN) g else g.map { GlossaryEntry(it.target, it.source) })
    }
    suspend fun setSpelling(s: String) { spelling = s; loaded[FR_EN]?.translator?.spelling = s }

    /** Exclusive access for long jobs (documents); [sample] text decides the direction under "auto". */
    suspend fun <T> exclusive(sample: String? = null, dir: String? = null, block: suspend (TextTranslator) -> T): T {
        val tr = translator(resolve(sample, dir))
        return decodeLock.withLock {
            val old = beam
            beam = docBeam
            try { withContext(Dispatchers.Default) { block(tr) } } finally { beam = old }
        }
    }

    class NoModelException(dir: String) : IllegalStateException(
        if (dir == FR_EN) "Download the French → English model first (Settings → Models)" else "Download the English → French model first (Settings → Models)")

    companion object {
        const val FR_EN = "fr-en"
        const val EN_FR = "en-fr"
    }
}
