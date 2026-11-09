package app.carnet.pdf

// Shared source: compiled against Apache PDFBox for desktop tests, and rewritten to
// com.tom_roush.pdfbox for the Android app by a Gradle task. Keep to the common 2.0 API.

import app.carnet.core.doc.RunMapper
import app.carnet.core.doc.Region
import app.carnet.core.layout.Block
import app.carnet.core.layout.Style
import app.carnet.core.layout.StyledRun
import app.carnet.core.layout.Layout
import app.carnet.core.layout.TextLine
import app.carnet.core.layout.TextMeasurer
import app.carnet.core.mt.TextTranslator
import app.carnet.core.text.Segmenter
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.pdfwriter.ContentStreamWriter
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.IdentityHashMap

/** Font files bundled with the app, chosen to match the source document's look. */
interface FontProvider {
    /** family: "sans", "serif", "calibri", "mono" */
    fun open(family: String, bold: Boolean, italic: Boolean): InputStream
    fun fallback(): InputStream
}

data class PdfStats(val pages: Int, val blocks: Int, val translated: Int, val scanned: Boolean)

/**
 * Translates a PDF in place: original text is removed from each page's content stream and the
 * English text is drawn in the same blocks, same colour, matching font family, size reduced only
 * when needed to fit. Images, vector graphics, links and page structure are untouched.
 */
class PdfTranslator(private val fonts: FontProvider) {

    private class Captured(val page: Int, val positions: List<TextPosition>, val colors: List<Int>)

    private inner class Stripper(private val colorOf: IdentityHashMap<TextPosition, Int>) : PDFTextStripper() {
        val lines = ArrayList<Captured>()
        var pageIndex = 0
        init { sortByPosition = true; setShouldSeparateByBeads(true) }
        override fun processTextPosition(text: TextPosition) {
            val c = try { graphicsState.nonStrokingColor.toRGB() } catch (_: Exception) { 0 }
            colorOf[text] = c
            super.processTextPosition(text)
        }
        override fun startPage(page: PDPage?) { super.startPage(page) }
        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            if (textPositions.isEmpty()) return
            lines.add(Captured(currentPageNo - 1, ArrayList(textPositions), textPositions.map { colorOf[it] ?: 0 }))
        }
    }

    /** Extracts positioned lines from every page (top-left coordinates in points). */
    fun extract(doc: PDDocument): List<TextLine> {
        val colors = IdentityHashMap<TextPosition, Int>()
        val s = Stripper(colors)
        s.getText(doc)
        val out = ArrayList<TextLine>()
        for (c in s.lines) {
            // A "line" from the stripper may contain several runs separated by large gaps (columns, table cells).
            var start = 0
            val ps = c.positions
            for (i in 1..ps.size) {
                val split = i == ps.size || run {
                    val a = ps[i - 1]; val b = ps[i]
                    val gap = b.xDirAdj - (a.xDirAdj + a.widthDirAdj)
                    gap > a.fontSizeInPt * 1.6f || gap < -a.fontSizeInPt * 4 || kotlin.math.abs(b.yDirAdj - a.yDirAdj) > a.fontSizeInPt * 0.6f
                }
                if (split) {
                    val part = ps.subList(start, i)
                    if (part.isNotEmpty()) out.add(lineOf(c.page, part, c.colors.subList(start, i)))
                    start = i
                }
            }
        }
        return out.filter { it.text.isNotBlank() }
    }

    private fun styleOf(p: TextPosition, color: Int): Style {
        val name = p.font?.name ?: ""
        val bold = name.contains(Regex("(?i)bold|black|heavy|semibold")) || p.font?.fontDescriptor?.isForceBold == true
        val italic = name.contains(Regex("(?i)italic|oblique")) || p.font?.fontDescriptor?.isItalic == true
        return Style(bold, italic, 0xFF000000.toInt() or color, name)
    }

    private fun lineOf(page: Int, ps: List<TextPosition>, colors: List<Int>): TextLine {
        val runs = ArrayList<StyledRun>()
        val sb = StringBuilder()
        for ((i, p) in ps.withIndex()) {
            var piece = p.unicode ?: ""
            if (i > 0) {
                val prev = ps[i - 1]
                val gap = p.xDirAdj - (prev.xDirAdj + prev.widthDirAdj)
                if (gap > p.widthOfSpace * 0.45f && !sb.endsWith(" ") && piece != " ") piece = " $piece"
            }
            sb.append(piece)
            val st = styleOf(p, colors[i])
            val last = runs.lastOrNull()
            // Spaces join the previous run so style boundaries fall on words.
            if (last != null && (last.style == st || piece.isBlank())) runs[runs.size - 1] = StyledRun(last.text + piece, last.style)
            else runs.add(StyledRun(piece, st))
        }
        val size = ps.map { it.fontSizeInPt }.sorted()[ps.size / 2].let { if (it < 1f) ps.first().heightDir * 1.4f else it }
        val left = ps.minOf { it.xDirAdj }; val right = ps.maxOf { it.xDirAdj + it.widthDirAdj }
        val baseline = ps.map { it.yDirAdj }.sorted()[ps.size / 2]
        val top = baseline - size * 0.8f; val bottom = baseline + size * 0.22f
        val main = runs.maxBy { it.text.length }.style
        return TextLine(page, left, top, right, bottom, sb.toString(), size, main.bold, main.italic, main.color, main.fontName, runs)
    }

    fun family(fontName: String): String {
        val n = fontName.substringAfter('+').lowercase()
        return when {
            n.contains("mono") || n.contains("courier") || n.contains("consol") -> "mono"
            n.contains("calibri") || n.contains("carlito") -> "calibri"
            listOf("times", "serif", "georgia", "garamond", "cambria", "caladea", "book", "palatino", "minion", "roman").any { n.contains(it) } && !n.contains("sans") -> "serif"
            else -> "sans"
        }
    }

    /**
     * Translates [input] into [output]. Returns stats; [PdfStats.scanned] is true when pages have
     * no text layer (the caller should OCR them instead).
     */
    fun translate(input: File, output: OutputStream, translator: TextTranslator, cancel: () -> Boolean = { false }, progress: (Int, Int) -> Unit = { _, _ -> }): PdfStats {
        PDDocument.load(input).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            val lines = extract(doc)
            val textPages = lines.map { it.page }.toSet()
            if (lines.isEmpty()) { doc.save(output); return PdfStats(doc.numberOfPages, 0, 0, true) }
            val blocks = Layout.blocks(lines)
            Layout.assignRoom(blocks, { p -> doc.getPage(p).cropBox.height - 24f }, { p -> doc.getPage(p).cropBox.width })
            val jobs = blocks.map { RunMapper.Job(it.runs) }
            RunMapper.run(jobs, translator, cancel, progress)
            val linguistic = jobs.count { it.result != null }

            val fontCache = HashMap<String, PDFont>()
            fun font(st: Style): PDFont = fontCache.getOrPut("${family(st.fontName)}${st.bold}${st.italic}") {
                fonts.open(family(st.fontName), st.bold, st.italic).use { PDType0Font.load(doc, it, true) }
            }
            val fallback by lazy { fonts.fallback().use { PDType0Font.load(doc, it, true) } }
            val measurer = TextMeasurer { text, size, st -> widthOf(font(st), fallback, text) * size / 1000f }

            for ((pi, page) in doc.pages.withIndex()) {
                if (cancel()) throw InterruptedException("cancelled")
                if (pi !in textPages) continue
                removeText(doc, page)
                val box = page.cropBox
                val pageH = box.height
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    blocks.forEachIndexed { bi, b ->
                        if (b.page != pi) return@forEachIndexed
                        val regions: List<Region> = jobs[bi].result ?: b.runs.map { Region(it.format, it.text) }
                        val placement = Layout.fit(b, regions, measurer)
                        for (pl in placement.lines) for (seg in pl.segments) {
                            if (seg.text.isBlank()) continue
                            val c = seg.style.color
                            cs.setNonStrokingColor((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
                            drawLine(cs, font(seg.style), fallback, placement.fontSize, box.lowerLeftX + seg.x, box.lowerLeftY + pageH - pl.baseline, seg.text)
                        }
                    }
                }
            }
            doc.documentInformation?.let { info -> info.title?.let { t -> if (Segmenter.isLinguistic(t)) info.title = translator.translate(t) } }
            doc.documentCatalog.language = "en"
            doc.save(output)
            return PdfStats(doc.numberOfPages, blocks.size, linguistic, false)
        }
    }

    /** Removes every text-showing operator (Tj, TJ, ', ") from the page's content, keeping graphics and images. */
    fun removeText(doc: PDDocument, page: PDPage) {
        val parser = PDFStreamParser(page)
        parser.parse()
        val tokens: List<Any> = parser.tokens
        val kept = ArrayList<Any>(tokens.size)
        val operands = ArrayList<Any>()
        for (t in tokens) {
            if (t is Operator) {
                val n = t.name
                if (n == "Tj" || n == "TJ" || n == "'" || n == "\"") {
                    // A ' or " also moves to the next line: keep the line move.
                    if (n == "'" || n == "\"") kept.add(Operator.getOperator("T*"))
                } else { kept.addAll(operands); kept.add(t) }
                operands.clear()
            } else operands.add(t)
        }
        kept.addAll(operands)
        val stream = PDStream(doc)
        stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(kept) }
        page.setContents(stream)
    }

    private fun widthOf(f: PDFont, fallback: PDFont, text: String): Float {
        var w = 0f
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val s = String(Character.toChars(cp))
            w += try { f.getStringWidth(s) } catch (_: Exception) { try { fallback.getStringWidth(s) } catch (_: Exception) { 500f } }
            i += Character.charCount(cp)
        }
        return w
    }

    /** Draws a line, switching to the fallback font for characters the main font lacks. */
    private fun drawLine(cs: PDPageContentStream, f: PDFont, fallback: PDFont, size: Float, x: Float, y: Float, text: String) {
        cs.beginText()
        cs.newLineAtOffset(x, y)
        var cur: PDFont? = null
        val run = StringBuilder()
        fun flush() { if (run.isNotEmpty()) { cs.setFont(cur!!, size); cs.showText(run.toString()); run.setLength(0) } }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val s = String(Character.toChars(cp))
            val use = when {
                canEncode(f, s) -> f
                canEncode(fallback, s) -> fallback
                else -> null
            }
            if (use != null) {
                if (use !== cur) { flush(); cur = use }
                run.append(s)
            }
            i += Character.charCount(cp)
        }
        flush()
        cs.endText()
    }

    private val encodable = HashMap<Pair<PDFont, String>, Boolean>()
    private fun canEncode(f: PDFont, s: String): Boolean = encodable.getOrPut(f to s) {
        try { f.encode(s); f.getStringWidth(s) >= 0 } catch (_: Exception) { false }
    }
}
