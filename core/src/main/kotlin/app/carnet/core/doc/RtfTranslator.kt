package app.carnet.core.doc

import app.carnet.core.mt.TextTranslator
import app.carnet.core.text.Segmenter
import java.io.InputStream
import java.io.OutputStream

/**
 * Translates RTF in place. Control words, groups, fonts, colours and paragraph formatting are kept;
 * each paragraph's text is translated as a whole and written where its first text piece was, so
 * character formatting inside a paragraph collapses to that first piece's.
 */
object RtfTranslator {
    private val skipDest = setOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "footer", "listtable", "listoverridetable",
        "rsidtbl", "generator", "themedata", "colorschememapping", "latentstyles", "datastore", "xmlnstbl", "mmathPr", "pgdsctbl", "fldinst", "bkmkstart", "bkmkend")

    private class Piece(val start: Int, val end: Int, val text: String)

    fun translate(input: InputStream, output: OutputStream, translator: TextTranslator, progress: Progress = object : Progress {}): DocStats {
        val src = String(input.readBytes(), Charsets.ISO_8859_1)
        val paragraphs = ArrayList<MutableList<Piece>>()
        var cur = ArrayList<Piece>()
        // Depth at which a skipped destination started (-1 = not skipping).
        val skipStack = ArrayList<Boolean>()
        var skipping = false
        var i = 0
        var ucSkip = 1
        fun endPara() { if (cur.isNotEmpty()) { paragraphs.add(cur); cur = ArrayList() } }
        while (i < src.length) {
            val c = src[i]
            when {
                c == '{' -> { skipStack.add(skipping); i++
                    // {\* ...} and known destinations are not body text.
                    if (src.startsWith("\\*", i)) skipping = true
                    else if (src.startsWith("\\", i)) { val w = Regex("\\\\([a-zA-Z]+)").find(src, i)?.takeIf { it.range.first == i }?.groupValues?.get(1); if (w != null && w in skipDest) skipping = true }
                }
                c == '}' -> { skipping = if (skipStack.isEmpty()) false else skipStack.removeAt(skipStack.size - 1); i++ }
                c == '\\' -> {
                    val m = Regex("\\\\([a-zA-Z]+)(-?\\d+)? ?|\\\\'([0-9a-fA-F]{2})|\\\\(.)").find(src, i)!!
                    val word = m.groupValues[1]; val hex = m.groupValues[3]; val sym = m.groupValues[4]
                    if (!skipping) when {
                        word == "par" || word == "line" || word == "row" || word == "cell" || word == "sect" || word == "page" -> endPara()
                        word == "uc" -> ucSkip = m.groupValues[2].toIntOrNull() ?: 1
                        word == "u" -> {
                            var end = m.range.last + 1
                            repeat(ucSkip) { if (end < src.length) { end = if (src.startsWith("\\'", end)) end + 4 else end + 1 } }
                            val n = m.groupValues[2].toInt().let { if (it < 0) it + 65536 else it }
                            cur.add(Piece(m.range.first, end, n.toChar().toString())); i = end; continue
                        }
                        word == "tab" -> cur.add(Piece(m.range.first, m.range.last + 1, "\t"))
                        word == "emdash" -> cur.add(Piece(m.range.first, m.range.last + 1, "—"))
                        word == "endash" -> cur.add(Piece(m.range.first, m.range.last + 1, "–"))
                        word == "lquote" || word == "rquote" -> cur.add(Piece(m.range.first, m.range.last + 1, "'"))
                        word == "ldblquote" || word == "rdblquote" -> cur.add(Piece(m.range.first, m.range.last + 1, "\""))
                        hex.isNotEmpty() -> cur.add(Piece(m.range.first, m.range.last + 1, String(byteArrayOf(hex.toInt(16).toByte()), charset("windows-1252"))))
                        sym == "~" -> cur.add(Piece(m.range.first, m.range.last + 1, " "))
                        sym == "{" || sym == "}" || sym == "\\" -> cur.add(Piece(m.range.first, m.range.last + 1, sym))
                    }
                    i = m.range.last + 1
                }
                c == '\r' || c == '\n' -> i++
                else -> {
                    var j = i
                    while (j < src.length && src[j] !in "{}\\\r\n") j++
                    if (!skipping) cur.add(Piece(i, j, src.substring(i, j)))
                    i = j
                }
            }
        }
        endPara()
        val texts = paragraphs.map { p -> p.joinToString("") { it.text } }
        val todo = texts.indices.filter { Segmenter.isLinguistic(texts[it]) }
        val out = translator.translateAll(todo.map { texts[it].trim() }, progress::cancelled) { d, t -> progress.update(d, t) }
        val replace = HashMap<Int, Pair<Int, String>>() // piece start → (end, replacement)
        todo.forEachIndexed { k, idx ->
            val pieces = paragraphs[idx]
            val lead = texts[idx].takeWhile { it.isWhitespace() }; val trail = texts[idx].takeLastWhile { it.isWhitespace() }
            replace[pieces.first().start] = pieces.first().end to encode(lead + out[k] + trail)
            for (p in pieces.drop(1)) replace[p.start] = p.end to ""
        }
        val sb = StringBuilder(src.length + 1024)
        var pos = 0
        for (start in replace.keys.sorted()) {
            if (start < pos) continue
            val (end, rep) = replace[start]!!
            sb.append(src, pos, start).append(rep); pos = end
        }
        sb.append(src, pos, src.length)
        output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        return DocStats(paragraphs = texts.size, translated = todo.size, characters = out.sumOf { it.length })
    }

    /** Text → RTF: escapes and \uN? for anything outside ASCII. */
    private fun encode(t: String): String = buildString {
        for (ch in t) when {
            ch == '\\' || ch == '{' || ch == '}' -> append('\\').append(ch)
            ch == '\t' -> append("\\tab ")
            ch.code in 32..126 -> append(ch)
            else -> append("\\u").append(if (ch.code > 32767) ch.code - 65536 else ch.code).append('?')
        }
    }
}
