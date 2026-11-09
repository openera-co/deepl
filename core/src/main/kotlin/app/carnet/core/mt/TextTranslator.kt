package app.carnet.core.mt

import app.carnet.core.text.Segmenter

/** Anything that can translate a list of sentences (the model, or a fake in tests). */
fun interface SentenceTranslator {
    fun translateSentences(sentences: List<String>, cancel: () -> Boolean): List<String>

    /** A slower, more careful pass (beam search) used to re-check suspicious sentences. */
    fun translateCarefully(sentences: List<String>, cancel: () -> Boolean): List<String> = translateSentences(sentences, cancel)
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
    /** Built-in school vocabulary and number normalisation (see SchoolTerms, FrenchNumbers). */
    schoolMode: Boolean = true,
    /** Source and target language ("fr" → "en" or "en" → "fr"). */
    val source: String = "fr",
    val target: String = "en",
) {
    /** French school vocabulary and number words only apply when translating French into English. */
    private val schoolMode = schoolMode && source == "fr" && target == "en"
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
                // Scripts the model has no vocabulary for (Chinese, Hindi, Arabic…) make it hallucinate:
                // they go through as placeholder tokens and are put back afterwards.
                val shielded = part.map { shieldForeign(prepare(it)) }
                val prepared = shielded.map { it.first }
                val res = engine.translateSentences(prepared, cancel).toMutableList()
                // Number guard: if digits don't survive translation, re-translate carefully and keep the better one.
                val suspicious = prepared.indices.filter { numberScore(prepared[it], res[it]) < 1.0 }
                if (suspicious.isNotEmpty()) {
                    val retry = engine.translateCarefully(suspicious.map { prepared[it] }, cancel)
                    suspicious.forEachIndexed { k, idx -> if (numberScore(prepared[idx], retry[k]) > numberScore(prepared[idx], res[idx])) res[idx] = retry[k] }
                }
                // Copy guard: titles like "Lycée Jean Moulin — La tectonique des plaques" are sometimes copied
                // unchanged as if they were names. Translate the parts between separators on their own.
                val copied = part.indices.filter { k -> res[k].trim() == prepared[k].trim() && separator.containsMatchIn(prepared[k]) }
                if (copied.isNotEmpty()) {
                    for (k in copied) {
                        val pieces = ArrayList<String>(); val seps = ArrayList<String>()
                        var p0 = 0
                        for (m in separator.findAll(prepared[k])) { pieces.add(prepared[k].substring(p0, m.range.first)); seps.add(m.value); p0 = m.range.last + 1 }
                        pieces.add(prepared[k].substring(p0))
                        val tr = engine.translateSentences(pieces.map { it.trim() }, cancel)
                        val sb = StringBuilder()
                        pieces.indices.forEach { i -> sb.append(if (pieces[i].isBlank()) pieces[i] else tr[i]); if (i < seps.size) sb.append(seps[i]) }
                        res[k] = sb.toString()
                    }
                }
                // Term guard: an English term inserted into the French sometimes gets garbled
                // ("past participles" → "participating pasts"). Retry with placeholder tokens, which the
                // model copies verbatim, and put the terms back.
                val garbled = part.indices.filter { k -> expectedTerms(part[k]).any { t -> !res[k].contains(t, ignoreCase = true) } }
                if (garbled.isNotEmpty()) {
                    val ph = garbled.map { withPlaceholders(part[it]) }
                    val retry = engine.translateSentences(ph.map { shieldForeign(it.first).first }, cancel)
                    garbled.forEachIndexed { k, idx ->
                        val (_, terms) = ph[k]
                        var t = retry[k]
                        if (terms.indices.all { n -> t.contains(TOKENS[n]) }) {
                            terms.forEachIndexed { n, term -> t = t.replaceFirst(TOKENS[n], term) }
                            // A term restored at the start of a sentence takes a capital.
                            if (t.firstOrNull()?.isLowerCase() == true && part[idx].trimStart().firstOrNull()?.isUpperCase() == true) t = t.replaceFirstChar { it.uppercase() }
                            res[idx] = t
                        }
                    }
                }
                for (k in part.indices) res[k] = unshield(res[k], shielded[k].second)
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

    /** Source-side preparation: numbers as digits, school terms, then the user's glossary (which wins). */
    private fun isAllCaps(s: String) = s.count { it.isLetter() } >= 4 && s.none { it.isLowerCase() }

    private fun prepare(s: String): String {
        // ALL-CAPS headings translate much better in sentence case; the result is capitalised again.
        var out = if (isAllCaps(s)) s.lowercase().replaceFirstChar { it.uppercase() } else s
        for ((re, target) in glossaryRules) out = re.replace(out) { target }
        if (schoolMode) {
            out = app.carnet.core.text.FrenchNumbers.normalize(out)
            out = app.carnet.core.text.SchoolTerms.apply(out)
        }
        return out
    }

    private fun shieldForeign(s: String): Pair<String, List<String>> {
        val spans = ArrayList<String>()
        val out = foreign.replace(s) { m -> if (spans.size < SHIELDS.size) { spans.add(m.value); SHIELDS[spans.size - 1] } else m.value }
        return out to spans
    }

    private fun unshield(t: String, spans: List<String>): String {
        var out = t
        spans.forEachIndexed { i, v -> out = if (out.contains(SHIELDS[i])) out.replaceFirst(SHIELDS[i], v) else out }
        return out
    }

    /** Glossary and school-term matches in [s] (after number normalisation), longest first, not overlapping. */
    private fun termMatches(s: String): List<Pair<IntRange, String>> {
        val all = ArrayList<Pair<IntRange, String>>()
        for ((re, target) in glossaryRules) re.findAll(s).forEach { all.add(it.range to target) }
        if (schoolMode) all.addAll(app.carnet.core.text.SchoolTerms.matches(s))
        val chosen = ArrayList<Pair<IntRange, String>>()
        for (m in all) if (chosen.none { it.first.first <= m.first.last && m.first.first <= it.first.last }) chosen.add(m)
        return chosen.sortedBy { it.first.first }
    }

    private fun normalized(s: String) = if (schoolMode) app.carnet.core.text.FrenchNumbers.normalize(s) else s

    private fun expectedTerms(s: String): List<String> = termMatches(normalized(s)).map { it.second }

    /** [s] with each term replaced by a token the model copies (ZQX, ZQY…), and the terms in order. */
    private fun withPlaceholders(s: String): Pair<String, List<String>> {
        val n = normalized(s)
        val ms = termMatches(n).take(TOKENS.size)
        val sb = StringBuilder(); var p = 0
        val terms = ArrayList<String>()
        for ((r, en) in ms) {
            sb.append(n, p, r.first).append(TOKENS[terms.size]); terms.add(en); p = r.last + 1
        }
        sb.append(n.substring(p))
        return sb.toString() to terms
    }

    /**
     * Source ranges that [prepare] rewrites (glossary, school terms, numbers) with their replacement.
     * Formatting projection uses them: a bold "huit" inside "huit heures" is found as "8" in the English.
     */
    fun replacements(text: String): List<Pair<IntRange, String>> {
        val out = ArrayList<Pair<IntRange, String>>()
        for ((re, target) in glossaryRules) re.findAll(text).forEach { out.add(it.range to target) }
        if (schoolMode) { out.addAll(app.carnet.core.text.FrenchNumbers.matches(text)); out.addAll(app.carnet.core.text.SchoolTerms.matches(text)) }
        return out
    }

    /** Share of the source's numbers found in the translation (1.0 = all, or none to check). */
    private fun numberScore(src: String, tr: String): Double {
        // Hallucinated content: a number that isn't in the source, or a phrase repeated back to back.
        val srcNums = digits.findAll(src).map { it.value }.toSet()
        val invented = digits.findAll(tr).map { it.value }.any { n -> n !in srcNums && n.length >= 2 && !(n.toIntOrNull()?.let { v -> v in 1..12 && hourWords.containsMatchIn(tr) } ?: false) }
        if (invented) return 0.5
        if (repeated.containsMatchIn(tr) || stutter.containsMatchIn(tr)) return 0.5
        val a = digits.findAll(src).map { it.value }.toList()
        if (a.isEmpty()) return if (digits.containsMatchIn(tr) && hourWords.containsMatchIn(tr)) 0.5 else 1.0
        val b = digits.findAll(tr).map { it.value }.toMutableList()
        var hit = 0
        for (n in a) {
            val alt = n.toIntOrNull()?.let { if (it in 13..23) (it - 12).toString() else null }
            val i = b.indexOf(n).takeIf { it >= 0 } ?: alt?.let { b.indexOf(it) }?.takeIf { it >= 0 }
            if (i != null) { hit++; b.removeAt(i) }
        }
        return hit.toDouble() / a.size
    }

    private val digits = Regex("\\d+")
    /** The same word or short phrase three or more times in a row (decoder loop). */
    private val stutter = Regex("(?i)\\b((?:\\w+\\W+){1,3}?)\\1{2,}")
    private val repeated = Regex("(?i)\\b(\\w+(?:\\W+\\w+){1,4})\\W+(?:and\\W+|, )?\\1\\b")
    private val hourWords = Regex("(?i)\\b(a\\.m\\.|p\\.m\\.|am|pm|o'clock)\\b")

    /** Small fixes that the sentence model gets wrong in a school context. */
    private fun postProcess(src: String, tr: String): String {
        var out = tr.trim()
        if (out.isEmpty()) return src
        if (isAllCaps(src)) return out.uppercase()
        // Keep the source's final punctuation style when the model drops or adds one.
        val srcEnd = src.trimEnd().lastOrNull()
        val outEnd = out.lastOrNull()
        if (srcEnd != null && srcEnd !in ".!?…:;" && outEnd == '.' && !src.trimEnd().endsWith(".")) out = out.dropLast(1)
        if (srcEnd == ':' && outEnd != ':') out = out.trimEnd('.', ' ') + ":"
        // A decoder loop that survived the careful retry: keep one occurrence.
        out = stutter.replace(out) { it.groupValues[1] }
        if (target == "fr") out = frenchTypography(out)
        else {
            // French spacing before punctuation must not leak into English.
            out = out.replace(Regex("\\s+([:;!?%])"), "$1")
            spelling?.let { out = app.carnet.core.text.Spelling.apply(out, it) }
        }
        // Preserve an initial lowercase (fragments inside formatted runs).
        val f = src.trimStart().firstOrNull()
        if (f != null && f.isLowerCase() && out.first().isUpperCase() && out.length > 1 && !out.startsWith("I ") && !(out[1].isUpperCase())) {
            out = out.first().lowercase() + out.substring(1)
        }
        return out
    }

    /** French punctuation spacing: no-break space before : ; ! ? and inside « ». */
    private fun frenchTypography(t: String): String {
        var out = t.replace(Regex("\\s*([;:!?])(?=\\s|$|[»)\"])"), "\u00A0$1")
        out = out.replace(Regex("«\\s*"), "«\u00A0").replace(Regex("\\s*»"), "\u00A0»")
        // Times and URLs keep their colon untouched ("10:30", "https://").
        out = out.replace(Regex("(\\d)\u00A0:(\\d)"), "$1:$2")
        return out
    }

    companion object {
        private val separator = Regex("\\s+[—–|·•/-]\\s+")
        private val TOKENS = listOf("ZQX", "ZQY", "ZQW", "ZQV", "ZQK", "ZQJ")
        private val SHIELDS = listOf("QZA", "QZB", "QZC", "QZD", "QZE", "QZF")
        private val foreign = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}\\p{IsDevanagari}\\p{IsArabic}\\p{IsHebrew}\\p{IsThai}\\p{IsBengali}\\p{IsTamil}][\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}\\p{IsDevanagari}\\p{IsArabic}\\p{IsHebrew}\\p{IsThai}\\p{IsBengali}\\p{IsTamil}\\p{M} 、・，]*")
        private val frArticle = Regex("^(?i)(?:(?:un|une|le|la|les|des|du|de la)\\s+|(?:l|de l)['’])")
        private val enArticle = Regex("^(?i)(a|an|the|some)\\s+")

        /** "un contrôle → a test" also matches "le contrôle", "ce contrôle"…: articles are dropped from both sides. */
        private fun compile(entries: List<GlossaryEntry>): List<Pair<Regex, String>> =
            entries.filter { it.source.isNotBlank() && it.target.isNotBlank() }
                .map { e ->
                    val src = e.source.trim(); val tgt = e.target.trim()
                    val bare = frArticle.replace(src, "")
                    if (bare.isNotBlank() && bare != src) bare to enArticle.replace(tgt, "") else src to tgt
                }
                .sortedByDescending { it.first.length }
                .map { (src, tgt) -> Regex("(?iu)(?<![\\p{L}\\p{N}])" + Regex.escape(src).replace("'", "['’]") + "(?![\\p{L}\\p{N}])") to tgt }
    }
}

/** Adapts [MarianModel] to [SentenceTranslator]. */
class MarianSentenceTranslator(private val model: MarianModel, private val beam: Int = 1) : SentenceTranslator {
    override fun translateSentences(sentences: List<String>, cancel: () -> Boolean): List<String> =
        model.translate(sentences, beam = beam, cancel = cancel)
    override fun translateCarefully(sentences: List<String>, cancel: () -> Boolean): List<String> =
        model.translate(sentences, beam = 4, cancel = cancel)
}
