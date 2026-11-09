package app.carnet.pdf

// Shared source: compiled against Apache PDFBox for desktop tests, and rewritten to
// com.tom_roush.pdfbox for the Android app by a Gradle task. Keep to the common 2.0 API.

import app.carnet.core.doc.RunMapper
import app.carnet.core.doc.Region
import app.carnet.core.layout.Block
import app.carnet.core.layout.Style
import app.carnet.core.layout.StyledRun
import app.carnet.core.layout.Layout
import app.carnet.core.layout.Rule
import app.carnet.core.layout.Rules
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
        /** Index of the current operator in the page's own content stream; -1 inside form XObjects. */
        private var opIndex = -1
        private var depth = 0
        val opOf = IdentityHashMap<TextPosition, Int>()
        val extraOps = IdentityHashMap<TextPosition, ArrayList<Int>>()
        private var lastGlyph: TextPosition? = null
        override fun startPage(page: PDPage?) { opIndex = -1; depth = 0; super.startPage(page) }
        private var nested = 0
        override fun processOperator(operator: Operator, operands: MutableList<org.apache.pdfbox.cos.COSBase>) {
            // Some operators run others internally (TD → TL + Td, ' → T* + Tj): count only stream tokens.
            val top = depth == 0 && nested == 0
            if (top) opIndex++
            val name = operator.name
            val shows = top && (name == "Tj" || name == "TJ")
            val before = if (shows) textMatrix?.clone() else null
            nested++
            try { super.processOperator(operator, operands) } finally { nested-- }
            if (shows && before != null) {
                // How far the operator moved the text position, as a TJ adjustment: removing the operator
                // must not shift text drawn after it in the same text object.
                val after = textMatrix ?: return
                val a = before.scaleX; val b = before.shearY
                val de = after.translateX - before.translateX; val df = after.translateY - before.translateY
                val norm = a * a + b * b
                val ts = graphicsState.textState
                val k = ts.fontSize * ts.horizontalScaling / 100f
                if (norm > 0f && k != 0f) disp[opIndex] = -((de * a + df * b) / norm) * 1000f / k
            }
        }
        private val forms = ArrayList<org.apache.pdfbox.cos.COSStream>()
        val formOf = IdentityHashMap<TextPosition, org.apache.pdfbox.cos.COSStream>()
        override fun showForm(form: org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject) {
            depth++; forms.add(form.cosObject)
            try { super.showForm(form) } finally { depth--; forms.removeAt(forms.size - 1) }
        }
        override fun showTransparencyGroup(form: org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup) {
            depth++; forms.add(form.cosObject)
            try { super.showTransparencyGroup(form) } finally { depth--; forms.removeAt(forms.size - 1) }
        }
        /** Number of page-level operators seen per page (checked against the parser before selective removal). */
        val opCount = HashMap<Int, Int>()
        /** TJ adjustment equivalent to each text-showing operator's advance, per page. */
        val disps = HashMap<Int, Map<Int, Float>>()
        private var disp = HashMap<Int, Float>()
        override fun endPage(page: PDPage?) {
            opCount[currentPageNo - 1] = opIndex + 1; disps[currentPageNo - 1] = disp; disp = HashMap()
            flushLine(); super.endPage(page)
        }
        init {
            // Content-stream order keeps columns apart (each column is drawn top to bottom);
            // sorting by position would merge lines of side-by-side columns.
            sortByPosition = false; setShouldSeparateByBeads(true)
            // PDFTextStripper ignores colour operators by default; text colour is needed.
            addOperator(org.apache.pdfbox.contentstream.operator.color.SetStrokingColorSpace())
            addOperator(org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace())
            addOperator(org.apache.pdfbox.contentstream.operator.color.SetStrokingColor())
            addOperator(org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColor())
            addOperator(org.apache.pdfbox.contentstream.operator.color.SetStrokingColorN())
            addOperator(org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorN())
            addOperator(org.apache.pdfbox.contentstream.operator.color.SetStrokingDeviceGrayColor())
            addOperator(org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor())
            addOperator(org.apache.pdfbox.contentstream.operator.color.SetStrokingDeviceRGBColor())
            addOperator(org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor())
            addOperator(org.apache.pdfbox.contentstream.operator.color.SetStrokingDeviceCMYKColor())
            addOperator(org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor())
            // Path operators: straight rules (table borders, box edges, underlines) are collected so text
            // never merges or grows across them.
            for (n in listOf("re", "m", "l", "h", "S", "s", "f", "F", "f*", "B", "B*", "b", "b*", "n", "c", "v", "y")) addOperator(PathOp(n))
        }

        val rules = HashMap<Int, Pair<ArrayList<Rule>, ArrayList<Rule>>>()
        private val segs = ArrayList<FloatArray>() // x0,y0,x1,y1 in device space
        private var px = 0f; private var py = 0f; private var sx = 0f; private var sy = 0f

        private fun dev(x: Float, y: Float): FloatArray {
            val m = graphicsState.currentTransformationMatrix
            return floatArrayOf(m.scaleX * x + m.shearX * y + m.translateX, m.shearY * x + m.scaleY * y + m.translateY)
        }

        private inner class PathOp(private val op: String) : org.apache.pdfbox.contentstream.operator.OperatorProcessor() {
            override fun getName() = op
            override fun process(operator: Operator, operands: MutableList<org.apache.pdfbox.cos.COSBase>) {
                if (depth > 0) { if (op in PAINT) segs.clear(); return }
                val v = operands.map { (it as? org.apache.pdfbox.cos.COSNumber)?.floatValue() ?: 0f }
                when (op) {
                    "re" -> if (v.size >= 4) {
                        val x = v[0]; val y = v[1]; val w = v[2]; val h = v[3]
                        val pts = listOf(dev(x, y), dev(x + w, y), dev(x + w, y + h), dev(x, y + h))
                        for (i in 0 until 4) segs.add(floatArrayOf(pts[i][0], pts[i][1], pts[(i + 1) % 4][0], pts[(i + 1) % 4][1]))
                    }
                    "m" -> if (v.size >= 2) { val d = dev(v[0], v[1]); px = d[0]; py = d[1]; sx = px; sy = py }
                    "l" -> if (v.size >= 2) { val d = dev(v[0], v[1]); segs.add(floatArrayOf(px, py, d[0], d[1])); px = d[0]; py = d[1] }
                    "c" -> if (v.size >= 6) { val d = dev(v[4], v[5]); px = d[0]; py = d[1] }
                    "v", "y" -> if (v.size >= 4) { val d = dev(v[2], v[3]); px = d[0]; py = d[1] }
                    "h" -> { segs.add(floatArrayOf(px, py, sx, sy)); px = sx; py = sy }
                    "n" -> segs.clear()
                    else -> commit(op)
                }
            }
        }

        private fun commit(op: String) {
            val box = currentPage.cropBox
            val (hs, vs) = rules.getOrPut(currentPageNo - 1) { ArrayList<Rule>() to ArrayList() }
            val filled = op != "S" && op != "s"
            // A filled shape only counts as a rule when it is thin (a drawn line); its edges are box edges otherwise.
            val xs = segs.flatMap { listOf(it[0], it[2]) }; val ys = segs.flatMap { listOf(it[1], it[3]) }
            if (segs.isNotEmpty() && filled && (xs.max() - xs.min() < 2.5f || ys.max() - ys.min() < 2.5f)) {
                val x0 = xs.min() - box.lowerLeftX; val x1 = xs.max() - box.lowerLeftX
                val y0 = box.upperRightY - ys.max(); val y1 = box.upperRightY - ys.min()
                if (x1 - x0 < 2.5f && y1 - y0 > 3f) vs.add(Rule((x0 + x1) / 2, y0, y1)) else if (x1 - x0 > 3f) hs.add(Rule((y0 + y1) / 2, x0, x1))
            } else for (g in segs) {
                val x0 = minOf(g[0], g[2]) - box.lowerLeftX; val x1 = maxOf(g[0], g[2]) - box.lowerLeftX
                val y0 = box.upperRightY - maxOf(g[1], g[3]); val y1 = box.upperRightY - minOf(g[1], g[3])
                if (x1 - x0 < 0.5f && y1 - y0 > 3f) vs.add(Rule(x0, y0, y1))
                else if (y1 - y0 < 0.5f && x1 - x0 > 3f) hs.add(Rule(y0, x0, x1))
            }
            segs.clear()
        }
        override fun processTextPosition(text: TextPosition) {
            val c = try { graphicsState.nonStrokingColor.toRGB() } catch (_: Exception) { 0 }
            colorOf[text] = c
            val op = if (depth == 0) opIndex else -1
            // Diacritics drawn separately get merged into the previous glyph by the stripper:
            // remember their operator with that glyph.
            if (text.isDiacritic && lastGlyph != null) extraOps.getOrPut(lastGlyph!!) { ArrayList() }.add(op) else lastGlyph = text
            opOf[text] = op
            forms.lastOrNull()?.let { formOf[text] = it }
            super.processTextPosition(text)
        }
        // PDFTextStripper reports a line word by word when the PDF positions words without space
        // characters (justified text from browsers, LaTeX, Word): collect the whole line here and
        // split it on real gaps (columns, table cells) in [extract].
        private val cur = ArrayList<TextPosition>()
        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            cur.addAll(textPositions)
        }
        override fun writeLineSeparator() { flushLine(); super.writeLineSeparator() }
        override fun writeParagraphEnd() { flushLine(); super.writeParagraphEnd() }
        private fun flushLine() {
            if (cur.isEmpty()) return
            lines.add(Captured(currentPageNo - 1, ArrayList(cur), cur.map { colorOf[it] ?: 0 }))
            cur.clear()
        }
    }

    /** Content-stream operator indices (page level) that draw each extracted line; -1 = inside a form XObject. */
    private val lineOps = IdentityHashMap<TextLine, IntArray>()
    private var opCounts: Map<Int, Int> = emptyMap()
    private var opDisps: Map<Int, Map<Int, Float>> = emptyMap()
    /** Rules (table borders, box edges) per page from the last [extract]. */
    var pageRules: Map<Int, Rules> = emptyMap(); private set
    fun rulesOf(page: Int): Rules = pageRules[page] ?: Rules.NONE
    /** Form XObjects (innermost) whose content draws each line. */
    private val lineForms = IdentityHashMap<TextLine, List<org.apache.pdfbox.cos.COSStream>>()
    /** For rotated lines: a glyph's (xDirAdj, yDirAdj) and its page position, to map layout coordinates back. */
    private val lineAnchor = IdentityHashMap<TextLine, FloatArray>()

    /** Extracts positioned lines from every page (top-left coordinates in points). */
    fun extract(doc: PDDocument): List<TextLine> {
        val colors = IdentityHashMap<TextPosition, Int>()
        val s = Stripper(colors)
        s.getText(doc)
        lineOps.clear(); lineForms.clear(); lineAnchor.clear()
        opCounts = s.opCount; opDisps = s.disps
        pageRules = s.rules.mapValues { (_, v) -> Rules(v.first, v.second) }
        class Part(val page: Int, val ps: List<TextPosition>, val colors: List<Int>) {
            val left by lazy { ps.minOf { it.xDirAdj } }
            val right by lazy { ps.maxOf { it.xDirAdj + it.widthDirAdj } }
            val base by lazy { ps.map { it.yDirAdj }.sorted()[ps.size / 2] }
            val size by lazy { ps.map { sizeOf(it) }.sorted()[ps.size / 2] }
        }
        val parts = ArrayList<Part>()
        for (c in s.lines) {
            // A "line" from the stripper may contain several runs separated by large gaps (columns, table cells).
            var start = 0
            val ps = c.positions
            for (i in 1..ps.size) {
                val split = i == ps.size || run {
                    val a = ps[i - 1]; val b = ps[i]
                    val gap = b.xDirAdj - (a.xDirAdj + a.widthDirAdj)
                    // A list marker set apart from its text ("•   text", "1.   text") stays where it is.
                    val marker = gap > sizeOf(a) * 0.45f && isMarker(ps.subList(start, i).joinToString("") { glyphText(it) }.trim())
                    val ruled = a.dir == 0f && s.rules[c.page]?.second?.any { r -> r.pos > a.xDirAdj + a.widthDirAdj * 0.5f && r.pos < b.xDirAdj + b.widthDirAdj * 0.5f && r.from < a.yDirAdj && r.to > a.yDirAdj - a.heightDir } == true
                    marker || ruled || a.dir != b.dir || gap > sizeOf(a) * 1.6f || gap < -sizeOf(a) * 4 || kotlin.math.abs(b.yDirAdj - a.yDirAdj) > sizeOf(a) * 0.6f
                }
                if (split) {
                    if (i > start) parts.add(Part(c.page, ps.subList(start, i), c.colors.subList(start, i)))
                    start = i
                }
            }
        }
        // Pieces of one line drawn out of order (font fallback runs, field results) are joined back
        // when they sit on the same baseline right next to each other.
        val merged = ArrayList<Part>()
        for (p in parts) {
            var joined = false
            for (k in merged.size - 1 downTo 0) {
                val q = merged[k]
                if (q.page != p.page) break
                if (q.ps[0].dir != p.ps[0].dir) continue
                val sz = maxOf(p.size, q.size)
                if (kotlin.math.abs(q.base - p.base) > 0.6f * sz) continue
                val raw = maxOf(p.left - q.right, q.left - p.right)
                val small = minOf(p.ps.size, q.ps.size) <= 4
                val inside = p.left >= q.left - 0.5f && p.right <= q.right + 0.5f || q.left >= p.left - 0.5f && q.right <= p.right + 0.5f
                // Sub- and superscripts drawn separately (xₙ, 1er, m²) join the line they sit on, even in its middle.
                // …but never glyphs drawn twice on top of each other (fake bold, shadows).
                fun collides() = p.ps.any { g -> q.ps.any { h -> minOf(g.xDirAdj + g.widthDirAdj, h.xDirAdj + h.widthDirAdj) - maxOf(g.xDirAdj, h.xDirAdj) > 0.5f * minOf(g.widthDirAdj, h.widthDirAdj) && kotlin.math.abs(g.yDirAdj - h.yDirAdj) < 0.2f * sz } }
                val script = small && kotlin.math.abs(q.base - p.base) < 0.55f * sz && (raw in -0.5f * sz..0.3f * sz || raw < 0f && inside) && !collides()
                val gap = if (script && raw < 0f) 0f else raw
                if (!script && (kotlin.math.abs(q.base - p.base) > 0.15f * sz || kotlin.math.abs(q.size - p.size) > 0.3f * sz)) continue
                if (gap > 0.9f * sz || gap < -0.5f * sz) continue
                if (isMarker(q.ps.joinToString("") { glyphText(it) }.trim()) && gap > 0.45f * sz) continue
                val (l1, l2) = if (q.left <= p.left) q to p else p to q
                if (s.rules[p.page]?.second?.any { r -> r.pos > l1.right - 0.5f && r.pos < l2.left + 0.5f && r.from < p.base && r.to > p.base - 0.6f * sz } == true) continue
                val all = (q.ps.indices.map { q.ps[it] to q.colors[it] } + p.ps.indices.map { p.ps[it] to p.colors[it] }).sortedBy { it.first.xDirAdj }
                merged[k] = Part(p.page, all.map { it.first }, all.map { it.second }); joined = true; break
            }
            if (!joined) merged.add(p)
        }
        val out = ArrayList<TextLine>()
        for (part in merged) {
            val line = lineOf(part.page, part.ps, part.colors)
            val g = part.ps[0]
            if (line.rotation != 0) lineAnchor[line] = floatArrayOf(g.xDirAdj, g.yDirAdj, g.textMatrix.translateX, g.textMatrix.translateY)
            lineOps[line] = part.ps.flatMap { listOf(s.opOf[it] ?: -1) + (s.extraOps[it] ?: emptyList<Int>()) }.distinct().toIntArray()
            part.ps.mapNotNull { s.formOf[it] }.distinct().takeIf { it.isNotEmpty() }?.let { lineForms[line] = it }
            out.add(line)
        }
        return out.filter { it.text.isNotBlank() }
    }

    /** Symbol-font bullets come out as private-use characters: map them to real ones. */
    private fun glyphText(p: TextPosition): String {
        val u = p.unicode ?: return ""
        if (u.length != 1 || u[0] !in '\uF000'..'\uF0FF') return u
        return PUA[u[0]] ?: u
    }

    private val PUA = mapOf('\uF0B7' to "•", '\uF0A7' to "▪", '\uF0A8' to "○", '\uF0D8' to "➢", '\uF0FC' to "✓", '\uF076' to "❖",
        '\uF06E' to "■", '\uF0E0' to "→", '\uF0E8' to "➔", '\uF0F0' to "⇨", '\uF02D' to "-", '\uF0BE' to "—", '\uF06C' to "●", '\uF071' to "❑", '\uF0A4' to "◦")

    private val PAINT = setOf("S", "s", "f", "F", "f*", "B", "B*", "b", "b*", "n")
    private val markerRe = Regex("^([•●◦▪■□▶►➢➤✓✔‣⁃○◆◇–—\\-*·]|\\(?\\d{1,3}[.)]|\\(?[a-zA-Z][.)]|[ivxIVX]{1,4}[.)])$")
    private fun isMarker(t: String) = t.isNotEmpty() && t.length <= 5 && markerRe.matches(t)

    /** Rendered font size in points: Tf size × text matrix × page transform (browsers scale px → pt). */
    private fun sizeOf(p: TextPosition): Float {
        val y = kotlin.math.abs(p.yScale)
        return if (y > 0.5f) y else p.fontSizeInPt
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
            var piece = glyphText(p)
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
        val size = ps.map { sizeOf(it) }.sorted()[ps.size / 2].let { if (it < 1f) ps.first().heightDir * 1.4f else it }
        val left = ps.minOf { it.xDirAdj }; val right = ps.maxOf { it.xDirAdj + it.widthDirAdj }
        val baseline = ps.map { it.yDirAdj }.sorted()[ps.size / 2]
        val top = baseline - size * 0.8f; val bottom = baseline + size * 0.22f
        val main = runs.maxBy { it.text.length }.style
        val rot = ((ps[0].dir.toInt() % 360) + 360) % 360
        return TextLine(page, left, top, right, bottom, sb.toString(), size, main.bold, main.italic, main.color, main.fontName, runs, rotation = rot)
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
            val blocks = Layout.blocks(lines, rules = ::rulesOf)
            Layout.assignRoom(blocks, { p -> doc.getPage(p).cropBox.height - 24f }, { p -> doc.getPage(p).cropBox.width }, ::rulesOf)
            val jobs = blocks.map { RunMapper.Job(it.runs) }
            RunMapper.run(jobs, translator, cancel, progress)
            val linguistic = jobs.count { it.result != null }

            val fontCache = HashMap<String, PDFont>()
            fun font(st: Style): PDFont = fontCache.getOrPut("${family(st.fontName)}${st.bold}${st.italic}") {
                fonts.open(family(st.fontName), st.bold, st.italic).use { PDType0Font.load(doc, it, true) }
            }
            val fallback by lazy { fonts.fallback().use { PDType0Font.load(doc, it, true) } }
            val measurer = TextMeasurer { text, size, st -> widthOf(font(st), fallback, text) * size / 1000f }

            // Only text that is replaced is removed: formulas, numbers and other untranslated text stay
            // exactly as drawn (original font and position). An operator shared with replaced text goes,
            // and then the untranslated block is redrawn.
            val removeOps = HashMap<Int, HashSet<Int>>()
            // Text inside form XObjects (SVG charts, stamps, reused headers) can't be removed operator by
            // operator from the page: all text of such a form is removed and every block in it redrawn.
            val removeForms = java.util.Collections.newSetFromMap(IdentityHashMap<org.apache.pdfbox.cos.COSStream, Boolean>())
            val replaced = BooleanArray(blocks.size) { jobs[it].result != null }
            fun opsOf(b: Block) = b.lines.flatMap { (lineOps[it] ?: intArrayOf()).asIterable() }.filter { it >= 0 }
            fun formsOf(b: Block) = b.lines.flatMap { lineForms[it] ?: emptyList() }
            var changed = true
            while (changed) {
                changed = false
                for ((bi, b) in blocks.withIndex()) {
                    val ops = opsOf(b); val fs = formsOf(b)
                    if (!replaced[bi] && (ops.any { removeOps[b.page]?.contains(it) == true } || fs.any { it in removeForms })) { replaced[bi] = true; changed = true }
                    if (replaced[bi]) {
                        if (removeOps.getOrPut(b.page) { HashSet() }.addAll(ops)) changed = true
                        if (removeForms.addAll(fs)) changed = true
                    }
                }
            }
            for (f in removeForms) removeFormText(f)

            for ((pi, page) in doc.pages.withIndex()) {
                if (cancel()) throw InterruptedException("cancelled")
                if (pi !in textPages) continue
                val selective = removeText(doc, page, removeOps[pi] ?: emptySet())
                val box = page.cropBox
                val pageH = box.height
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    blocks.forEachIndexed { bi, b ->
                        if (b.page != pi || (selective && !replaced[bi])) return@forEachIndexed
                        val regions: List<Region> = jobs[bi].result ?: b.runs.map { Region(it.format, it.text) }
                        val placement = Layout.fit(b, regions, measurer)
                        for (pl in placement.lines) for (seg in pl.segments) {
                            if (seg.text.isBlank()) continue
                            val c = seg.style.color
                            cs.setNonStrokingColor((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
                            val rot = b.lines.first().rotation
                            val anchor = lineAnchor[b.lines.first()]
                            if (rot == 0 || anchor == null) drawLine(cs, font(seg.style), fallback, placement.fontSize, box.lowerLeftX + seg.x, box.lowerLeftY + pageH - pl.baseline, seg.text)
                            else {
                                // Map the direction-adjusted layout position back to the page and draw along the text direction.
                                val dx = seg.x - anchor[0]; val dy = pl.baseline - anchor[1]
                                val (px, py) = when (rot) {
                                    90 -> (anchor[2] + dy) to (anchor[3] + dx)
                                    180 -> (anchor[2] - dx) to (anchor[3] + dy)
                                    else -> (anchor[2] - dy) to (anchor[3] - dx)
                                }
                                drawLine(cs, font(seg.style), fallback, placement.fontSize, px, py, seg.text, rot)
                            }
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
    private fun removeFormText(form: org.apache.pdfbox.cos.COSStream) {
        val parser = PDFStreamParser(org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject(form))
        parser.parse()
        val kept = ArrayList<Any>()
        val operands = ArrayList<Any>()
        for (t in parser.tokens) {
            if (t is Operator) {
                val n = t.name
                if (n == "Tj" || n == "TJ" || n == "'" || n == "\"") { if (n == "'" || n == "\"") kept.add(Operator.getOperator("T*")) }
                else { kept.addAll(operands); kept.add(t) }
                operands.clear()
            } else operands.add(t)
        }
        kept.addAll(operands)
        form.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(kept) }
    }

    /** Returns false when it had to remove all text (the stream didn't match the extraction). */
    fun removeText(doc: PDDocument, page: PDPage, only: Set<Int>? = null): Boolean {
        val pageIndex = doc.pages.indexOf(page)
        val parser = PDFStreamParser(page)
        parser.parse()
        val tokens: List<Any> = parser.tokens
        val kept = ArrayList<Any>(tokens.size)
        val operands = ArrayList<Any>()
        var index = -1
        for (t in tokens) {
            if (t is Operator) {
                index++
                val n = t.name
                if ((n == "Tj" || n == "TJ" || n == "'" || n == "\"") && (only == null || index in only)) {
                    // ' and " also move to the next line (" also sets word and character spacing).
                    if (n == "\"" && operands.size >= 3) { kept.add(operands[0]); kept.add(Operator.getOperator("Tw")); kept.add(operands[1]); kept.add(Operator.getOperator("Tc")) }
                    if (n == "'" || n == "\"") kept.add(Operator.getOperator("T*"))
                    // Keep the text position moving as if the text were drawn.
                    val d = opDisps[pageIndex]?.get(index)
                    if (only != null && d != null && d != 0f) {
                        kept.add(org.apache.pdfbox.cos.COSArray().apply { add(org.apache.pdfbox.cos.COSFloat(d)) }); kept.add(Operator.getOperator("TJ"))
                    }
                } else { kept.addAll(operands); kept.add(t) }
                operands.clear()
            } else operands.add(t)
        }
        kept.addAll(operands)
        if (only != null && index + 1 != opCounts[doc.pages.indexOf(page)]) { removeText(doc, page, null); return false }
        val stream = PDStream(doc)
        stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(kept) }
        page.setContents(stream)
        return true
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
    private fun drawLine(cs: PDPageContentStream, f: PDFont, fallback: PDFont, size: Float, x: Float, y: Float, text: String, rotation: Int = 0) {
        val ux = kotlin.math.cos(Math.toRadians(rotation.toDouble())).toFloat(); val uy = kotlin.math.sin(Math.toRadians(rotation.toDouble())).toFloat()
        fun at(along: Float, up: Float) = org.apache.pdfbox.util.Matrix(ux, uy, -uy, ux, x + along * ux - up * uy, y + along * uy + up * ux)
        cs.beginText()
        var pos = 0f
        var cur: PDFont? = null
        val run = StringBuilder()
        fun width(font: PDFont, t: String) = try { font.getStringWidth(t) * size / 1000f } catch (_: Exception) { 0f }
        fun flush() {
            if (run.isEmpty()) return
            cs.setTextMatrix(at(pos, 0f))
            cs.setFont(cur!!, size); cs.showText(run.toString())
            pos += width(cur!!, run.toString()); run.setLength(0)
        }
        fun pick(t: String) = when { canEncode(f, t) -> f; canEncode(fallback, t) -> fallback; else -> null }
        var i = 0
        while (i < text.length) {
            val baseEnd = i + Character.charCount(text.codePointAt(i))
            var j = baseEnd
            while (j < text.length && Character.getType(text.codePointAt(j)) == Character.NON_SPACING_MARK.toInt()) j += Character.charCount(text.codePointAt(j))
            val base = text.substring(i, baseEnd)
            // A mark without a base character (left over from extraction) is dropped.
            if (Character.getType(base.codePointAt(0)) == Character.NON_SPACING_MARK.toInt()) { i = j; continue }
            val use = pick(base)
            if (use != null) {
                if (use !== cur) { flush(); cur = use }
                run.append(base)
            }
            if (j > baseEnd && use != null) {
                // Combining marks (x̄, ŷ): drawn as their spacing form centred over the base glyph, since
                // fonts position zero-width marks inconsistently without a shaping engine.
                flush()
                val baseW = width(use, base)
                for (m in text.substring(baseEnd, j)) {
                    val sp = SPACING_MARKS[m] ?: continue
                    val mf = pick(sp) ?: continue
                    val mw = width(mf, sp)
                    cs.setTextMatrix(at(pos - baseW + (baseW - mw) / 2, if (base[0].isUpperCase() || base[0] in "bdfhklt") size * 0.18f else 0f))
                    cs.setFont(mf, size); cs.showText(sp)
                }
            }
            i = j
        }
        flush()
        cs.endText()
    }

    private val SPACING_MARKS = mapOf('\u0300' to "`", '\u0301' to "´", '\u0302' to "ˆ", '\u0303' to "˜", '\u0304' to "¯", '\u0305' to "¯",
        '\u0306' to "˘", '\u0307' to "˙", '\u0308' to "¨", '\u030A' to "˚", '\u030C' to "ˇ", '\u20D7' to "→")

    private val encodable = HashMap<Pair<PDFont, String>, Boolean>()
    private fun canEncode(f: PDFont, s: String): Boolean = encodable.getOrPut(f to s) {
        try { f.encode(s); f.getStringWidth(s) >= 0 } catch (_: Exception) { false }
    }
}
