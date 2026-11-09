package app.carnet.core.doc

import app.carnet.core.mt.TextTranslator
import app.carnet.core.text.Segmenter

/** One piece of formatted text inside a paragraph. [format] is a zone-local style id. */
data class RunText(val format: Int, val text: String)

/** Translated output for a zone: consecutive regions, each with the style it should carry. */
data class Region(val format: Int, val text: String)

/**
 * Maps formatting from source runs onto a translated paragraph.
 *
 * The paragraph is translated as a whole (so sentences stay fluent). Each differently formatted
 * span (bold term, italic word, coloured note…) is also translated on its own and located inside
 * the paragraph translation; that region receives the span's formatting. When a span cannot be
 * found (the model rephrased it), its position is projected proportionally, snapped to words.
 */
object RunMapper {

    class Job(val runs: List<RunText>) {
        val full: String = runs.joinToString("") { it.text }
        internal var translated: String? = null
        internal val spans = ArrayList<Span>()
        internal var baseFormat: Int = runs.firstOrNull()?.format ?: 0
        var result: List<Region>? = null
            internal set
    }

    internal class Span(val format: Int, val text: String, val start: Int) {
        var translated: String? = null
    }

    /** Translates all [jobs] with as few batched model calls as possible. */
    fun run(jobs: List<Job>, translator: TextTranslator, cancel: () -> Boolean = { false }, progress: (Int, Int) -> Unit = { _, _ -> }) {
        val active = jobs.filter { Segmenter.isLinguistic(it.full) }
        val requests = ArrayList<String>()
        for (j in active) {
            requests.add(j.full)
            val counts = HashMap<Int, Int>()
            for (r in j.runs) counts.merge(r.format, r.text.length, Int::plus)
            if (counts.size <= 1) continue
            val base = counts.maxBy { it.value }.key
            var pos = 0
            var cur: Span? = null
            for (r in j.runs) {
                if (r.format != base) {
                    if (cur != null && cur.format == r.format) cur = Span(cur.format, cur.text + r.text, cur.start).also { j.spans[j.spans.size - 1] = it }
                    else { cur = Span(r.format, r.text, pos); j.spans.add(cur) }
                } else cur = null
                pos += r.text.length
            }
            j.baseFormat = base
            for (s in j.spans) if (Segmenter.isLinguistic(s.text)) requests.add(s.text.trim())
        }
        val out = translator.translateAll(requests, cancel, progress)
        var k = 0
        for (j in active) {
            j.translated = out[k++]
            for (s in j.spans) if (Segmenter.isLinguistic(s.text)) s.translated = out[k++]
        }
        for (j in jobs) j.result = if (j.translated == null) null else build(j)
    }

    private fun build(j: Job): List<Region> {
        val t = j.translated!!
        if (j.spans.isEmpty()) return listOf(Region(j.baseFormat, t))
        val base = j.baseFormat
        val marks = ArrayList<Triple<Int, Int, Int>>() // start, end, format
        var cursor = 0
        val fullLen = j.full.length.coerceAtLeast(1)
        for (s in j.spans) {
            val core = s.text.trim()
            if (core.isEmpty()) continue
            val candidates = LinkedHashSet<String>()
            s.translated?.trim()?.let { tr ->
                candidates.add(tr)
                candidates.add(tr.trimEnd('.', ':', ';', ',', '!', '?'))
                for (art in listOf("the ", "a ", "an ", "The ", "A ", "An ")) if (tr.startsWith(art)) candidates.add(tr.removePrefix(art).trimEnd('.', ':', ';', ','))
            }
            candidates.add(core)
            candidates.add(core.trimEnd('.', ':', ';', ','))
            var found: IntRange? = null
            for (c in candidates) {
                if (c.isBlank()) continue
                val at = findWord(t, c, cursor)
                if (at >= 0) { found = at until at + c.length; break }
            }
            if (found == null) found = s.translated?.let { fuzzy(t, it, cursor) }
            if (found == null) {
                // Proportional projection, snapped to word boundaries.
                val leading = s.text.length - s.text.trimStart().length
                var a = ((s.start + leading).toDouble() / fullLen * t.length).toInt().coerceIn(cursor, t.length)
                var b = (a + core.length.toDouble() / fullLen * t.length).toInt().coerceIn(a, t.length)
                while (a > cursor && a < t.length && !t[a - 1].isWhitespace()) a--
                while (a < b && t[a].isWhitespace()) a++
                while (b < t.length && !t[b].isWhitespace() && t[b].isLetterOrDigit()) b++
                if (b <= a) continue
                found = a until b
            }
            marks.add(Triple(found.first, found.last + 1, s.format))
            cursor = found.last + 1
        }
        val regions = ArrayList<Region>()
        var p = 0
        for ((a, b, f) in marks) {
            if (a > p) regions.add(Region(base, t.substring(p, a)))
            regions.add(Region(f, t.substring(a, b)))
            p = b
        }
        if (p < t.length) regions.add(Region(base, t.substring(p)))
        // If the paragraph starts with a non-base span in the source, ensure the first region style matches.
        return regions.filter { it.text.isNotEmpty() }
    }

    private val wordRe = Regex("[\\p{L}\\p{N}']+")
    private val stop = setOf("the", "a", "an", "of", "to", "in", "and", "is", "are", "it", "its", "on", "for", "by", "at", "be")

    /**
     * Finds the window of words in [hay] (after [from]) that best matches [phrase] by word overlap,
     * for when the model rephrased a formatted span differently in context.
     */
    private fun fuzzy(hay: String, phrase: String, from: Int): IntRange? {
        val target = wordRe.findAll(phrase).map { it.value.lowercase() }.toList()
        if (target.isEmpty()) return null
        val content = target.filter { it !in stop }.toSet().ifEmpty { target.toSet() }
        val words = wordRe.findAll(hay).filter { it.range.first >= from }.toList()
        var best: IntRange? = null; var bestScore = 0.0
        for (len in maxOf(1, target.size - 2)..(target.size + 2)) {
            for (i in 0..words.size - len) {
                val win = words.subList(i, i + len)
                if (win.first().value.lowercase() in stop && len > 1) continue
                val hits = win.count { it.value.lowercase() in content }
                if (hits == 0) continue
                val score = hits.toDouble() / maxOf(content.size, win.count { it.value.lowercase() !in stop }) - 0.01 * kotlin.math.abs(len - target.size)
                if (score > bestScore) { bestScore = score; best = win.first().range.first..win.last().range.last }
            }
        }
        return if (bestScore >= 0.3) best else null
    }

    /** Case-insensitive search for [needle] in [hay] from [from], at word boundaries. */
    private fun findWord(hay: String, needle: String, from: Int): Int {
        var i = hay.indexOf(needle, from, ignoreCase = true)
        while (i >= 0) {
            val before = i == 0 || !hay[i - 1].isLetterOrDigit()
            val endIdx = i + needle.length
            val after = endIdx >= hay.length || !hay[endIdx].isLetterOrDigit() || !needle.last().isLetterOrDigit()
            if (before && after) return i
            i = hay.indexOf(needle, i + 1, ignoreCase = true)
        }
        return -1
    }
}
