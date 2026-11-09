package app.carnet.core.mt

import app.carnet.core.text.Segmenter

/** Anything that can translate a list of sentences (the model, or a fake in tests). */
fun interface SentenceTranslator {
    fun translateSentences(sentences: List<String>, cancel: () -> Boolean): List<String>
}

data class GlossaryEntry(val source: String, val target: String)

/**
 * Translates arbitrary French text (paragraphs, lists, line breaks) to English.
 * - keeps whitespace, line breaks, list markers, numbers, formulas and URLs untouched
 * - batches all sentences of a request into as few model calls as possible
 * - caches sentence translations (documents repeat headers, footers, labels)
 * - applies the user's glossary by substituting terms before translation
 */
class TextTranslator(
    private val engine: SentenceTranslator,
    glossary: List<GlossaryEntry> = emptyList(),
    cacheSize: Int = 4096,
) {
    @Volatile private var glossaryRules: List<Pair<Regex, String>> = compile(glossary)

    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > cacheSize
    }

    @Volatile var spelling: String? = null
        set(v) { if (field != v) { field = v; synchronized(cache) { cache.clear() } } }

    fun setGlossary(entries: List<GlossaryEntry>) {
        glossaryRules = compile(entries)
        synchronized(cache) { cache.clear() }
    }

    /** Translates one text. */
    fun translate(text: String, cancel: () -> Boolean = { false }): String = translateAll(listOf(text), cancel)[0]

    /** Translates many texts in one batched pass. Output i corresponds to input i. */
    fun translateAll(texts: List<String>, cancel: () -> Boolean = { false }, progress: (Int, Int) -> Unit = { _, _ -> }): List<String> {
        val segmented = texts.map { Segmenter.split(it) }
        val pending = LinkedHashSet<String>()
        for (segs in segmented) for (s in segs) if (s.translatable) {
            val key = keyOf(s.text)
            if (key.isNotEmpty() && synchronized(cache) { cache[key] } == null) pending.add(key)
        }
        if (pending.isNotEmpty()) {
            val list = pending.toList()
            // Translate in chunks so progress can be reported and cancellation is responsive.
            val chunk = 64
            var done = 0
            for (i in list.indices step chunk) {
                if (cancel()) throw InterruptedException("cancelled")
                val part = list.subList(i, minOf(list.size, i + chunk))
                val res = engine.translateSentences(part.map { applyGlossary(it) }, cancel)
                synchronized(cache) { part.forEachIndexed { k, src -> cache[src] = postProcess(src, res[k]) } }
                done += part.size
                progress(done, list.size)
            }
        }
        return segmented.map { segs ->
            val sb = StringBuilder()
            for (s in segs) {
                if (!s.translatable) { sb.append(s.text); continue }
                val key = keyOf(s.text)
                val lead = s.text.substring(0, s.text.length - s.text.trimStart().length)
                val trail = s.text.substring(s.text.trimEnd().length)
                val tr = synchronized(cache) { cache[key] } ?: key
                sb.append(lead).append(tr).append(trail)
            }
            sb.toString()
        }
    }

    private fun keyOf(s: String) = s.trim()

    private fun applyGlossary(s: String): String {
        var out = s
        for ((re, target) in glossaryRules) out = re.replace(out) { target }
        return out
    }

    /** Small fixes that the sentence model gets wrong in a school context. */
    private fun postProcess(src: String, tr: String): String {
        var out = tr.trim()
        if (out.isEmpty()) return src
        // Keep the source's final punctuation style when the model drops or adds one.
        val srcEnd = src.trimEnd().lastOrNull()
        val outEnd = out.lastOrNull()
        if (srcEnd != null && srcEnd !in ".!?…:;" && outEnd == '.' && !src.trimEnd().endsWith(".")) out = out.dropLast(1)
        if (srcEnd == ':' && outEnd != ':') out = out.trimEnd('.', ' ') + ":"
        // French spacing before punctuation must not leak into English.
        out = out.replace(Regex("\\s+([:;!?%])"), "$1")
        spelling?.let { out = app.carnet.core.text.Spelling.apply(out, it) }
        // Preserve an initial lowercase (fragments inside formatted runs).
        val f = src.trimStart().firstOrNull()
        if (f != null && f.isLowerCase() && out.first().isUpperCase() && out.length > 1 && !out.startsWith("I ") && !(out[1].isUpperCase())) {
            out = out.first().lowercase() + out.substring(1)
        }
        return out
    }

    companion object {
        private fun compile(entries: List<GlossaryEntry>): List<Pair<Regex, String>> =
            entries.filter { it.source.isNotBlank() && it.target.isNotBlank() }
                .sortedByDescending { it.source.length }
                .map { Regex("(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(it.source.trim()) + "(?![\\p{L}\\p{N}])") to it.target.trim() }
    }
}

/** Adapts [MarianModel] to [SentenceTranslator]. */
class MarianSentenceTranslator(private val model: MarianModel, private val beam: Int = 1) : SentenceTranslator {
    override fun translateSentences(sentences: List<String>, cancel: () -> Boolean): List<String> =
        model.translate(sentences, beam = beam, cancel = cancel)
}
