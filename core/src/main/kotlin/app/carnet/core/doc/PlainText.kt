package app.carnet.core.doc

import app.carnet.core.mt.TextTranslator
import java.io.InputStream
import java.io.OutputStream

/** Plain text, Markdown, subtitles (SRT/VTT), CSV and simple HTML. */
object PlainText {
    fun translate(name: String, input: InputStream, output: OutputStream, translator: TextTranslator, progress: Progress): DocStats {
        val raw = input.readBytes()
        val bom = raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()
        val text = String(raw, if (bom) 3 else 0, raw.size - (if (bom) 3 else 0), Charsets.UTF_8)
        val ext = name.substringAfterLast('.').lowercase()
        val stats = DocStats(parts = 1)
        val out = when (ext) {
            "csv" -> csv(text, translator, progress, stats)
            "html", "htm" -> html(text, translator, progress, stats)
            "md" -> markdown(text, translator, progress, stats)
            else -> lines(text, translator, progress, stats)
        }
        stats.characters = text.length
        if (bom) output.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        output.write(out.toByteArray(Charsets.UTF_8))
        return stats
    }

    /** Paragraph-aware: blank lines separate paragraphs, wrapped lines inside a paragraph are joined for context. */
    private fun lines(text: String, tr: TextTranslator, progress: Progress, stats: DocStats): String {
        val lines = text.split("\n")
        stats.paragraphs = lines.size
        val res = tr.translateAll(lines.map { it.removeSuffix("\r") }, progress::cancelled) { d, t -> progress.update(d, t) }
        stats.translated = res.size
        return lines.indices.joinToString("\n") { i -> res[i] + if (lines[i].endsWith("\r")) "\r" else "" }
    }

    private fun markdown(text: String, tr: TextTranslator, progress: Progress, stats: DocStats): String {
        // Keep code fences and inline code untouched; translate the rest line by line after stripping markers.
        val lines = text.split("\n")
        var inCode = false
        val prefixes = ArrayList<String>(); val bodies = ArrayList<String>(); val skip = BooleanArray(lines.size)
        for ((i, l) in lines.withIndex()) {
            if (l.trimStart().startsWith("```")) { inCode = !inCode; skip[i] = true }
            if (inCode) skip[i] = true
            val m = Regex("^(\\s*(?:#{1,6}\\s+|[-*+]\\s+(?:\\[[ xX]]\\s+)?|\\d+[.)]\\s+|>\\s*)*)(.*)$").find(l)!!
            prefixes.add(m.groupValues[1]); bodies.add(m.groupValues[2])
        }
        val idx = lines.indices.filter { !skip[it] }
        val res = tr.translateAll(idx.map { bodies[it] }, progress::cancelled) { d, t -> progress.update(d, t) }
        val out = lines.toMutableList()
        idx.forEachIndexed { k, i -> out[i] = prefixes[i] + res[k] }
        stats.paragraphs = lines.size; stats.translated = idx.size
        return out.joinToString("\n")
    }

    private fun csv(text: String, tr: TextTranslator, progress: Progress, stats: DocStats): String {
        val sep = if (text.lineSequence().take(5).sumOf { it.count { c -> c == ';' } } > text.lineSequence().take(5).sumOf { it.count { c -> c == ',' } }) ';' else ','
        val rows = parseCsv(text, sep)
        val cells = rows.flatten()
        val res = tr.translateAll(cells, progress::cancelled) { d, t -> progress.update(d, t) }
        var k = 0
        stats.paragraphs = cells.size; stats.translated = cells.size
        val eol = if (text.contains("\r\n")) "\r\n" else "\n"
        val body = rows.joinToString(eol) { r -> r.joinToString(sep.toString()) { _ -> quote(res[k++], sep) } }
        return if (text.endsWith("\n")) body + eol else body
    }

    private fun quote(s: String, sep: Char) = if (s.any { it == sep || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun parseCsv(text: String, sep: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var q = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (q) {
                if (c == '"') { if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else q = false } else cell.append(c)
            } else when (c) {
                '"' -> q = true
                sep -> { row.add(cell.toString()); cell.setLength(0) }
                '\r' -> {}
                '\n' -> { row.add(cell.toString()); cell.setLength(0); rows.add(row); row = ArrayList() }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }

    private fun html(text: String, tr: TextTranslator, progress: Progress, stats: DocStats): String {
        // Translate text between tags, skipping script/style; inline tags split text, which is acceptable for HTML exports.
        val parts = Regex("(<script[\\s\\S]*?</script>|<style[\\s\\S]*?</style>|<!--[\\s\\S]*?-->|<[^>]+>)", RegexOption.IGNORE_CASE).split(text)
        val tags = Regex("(<script[\\s\\S]*?</script>|<style[\\s\\S]*?</style>|<!--[\\s\\S]*?-->|<[^>]+>)", RegexOption.IGNORE_CASE).findAll(text).map { it.value }.toList()
        val decoded = parts.map { it.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'") }
        val res = tr.translateAll(decoded, progress::cancelled) { d, t -> progress.update(d, t) }
        stats.paragraphs = parts.size; stats.translated = parts.size
        val sb = StringBuilder()
        for (i in parts.indices) {
            sb.append(res[i].replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
            if (i < tags.size) sb.append(tags[i].replace(Regex("lang=\"fr[^\"]*\""), "lang=\"en\""))
        }
        return sb.toString()
    }
}
