package app.carnet.core.convert

import app.carnet.core.layout.TextMeasurer
import kotlin.math.max
import kotlin.math.min

/** Drawing operations for one output page. Coordinates: points, origin top-left. */
sealed class Op
data class TextOp(val x: Float, val baseline: Float, val text: String, val style: CharStyle, val width: Float) : Op()
data class RectOp(val x: Float, val y: Float, val w: Float, val h: Float, val fill: Int?, val stroke: Int?, val strokeWidth: Float = 0.5f) : Op()
data class ImageOp(val x: Float, val y: Float, val w: Float, val h: Float, val image: ImageRun) : Op()
data class LineOp(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val color: Int, val width: Float) : Op()

class RenderPage(val width: Float, val height: Float) { val ops = ArrayList<Op>() }

/**
 * Flows a [DocModel] onto pages: word wrap with mixed styles, alignment, lists, spacing,
 * tables (rows never split), images, headers/footers with page numbers, and fixed slide pages.
 */
class Paginator(private val m: TextMeasurer) {

    private fun w(text: String, s: CharStyle) = m.width(text, effSize(s), s.toStyle())
    private fun effSize(s: CharStyle) = if (s.vertAlign != 0) s.size * 0.65f else s.size

    fun paginate(doc: DocModel): List<RenderPage> {
        val pages = ArrayList<RenderPage>()
        for (sec in doc.sections) {
            if (sec.fixedPages.isNotEmpty()) {
                for (fp in sec.fixedPages) pages.add(fixed(sec.page, fp))
                continue
            }
            val start = pages.size
            flow(sec, pages)
            // Headers/footers after layout so NUMPAGES is known.
            val total = pages.size - start
            for (i in start until pages.size) {
                val p = pages[i]
                val ctx = Ctx(sec.page.marginLeft, sec.page.width - sec.page.marginRight)
                var y = sec.page.headerY
                for (b in sec.header) y = drawStatic(p, b, ctx, y, i - start + 1, total)
                // Footer: measure first, then place so its bottom sits at footerY from the page bottom.
                val fh = sec.footer.sumOf { measureBlock(it, ctx.right - ctx.left).toDouble() }.toFloat()
                var fy = sec.page.height - sec.page.footerY - fh
                for (b in sec.footer) fy = drawStatic(p, b, ctx, fy, i - start + 1, total)
            }
        }
        if (pages.isEmpty()) pages.add(RenderPage(PageSetup().width, PageSetup().height))
        return pages
    }

    private class Ctx(val left: Float, val right: Float)

    private fun flow(sec: Section, pages: MutableList<RenderPage>) {
        val ps = sec.page
        var page = RenderPage(ps.width, ps.height).also { pages.add(it) }
        var y = ps.marginTop
        val bottom = ps.height - ps.marginBottom
        val ctx = Ctx(ps.marginLeft, ps.width - ps.marginRight)
        fun newPage() { page = RenderPage(ps.width, ps.height).also { pages.add(it) }; y = ps.marginTop }
        var prevAfter = 0f
        for ((bi, b) in sec.blocks.withIndex()) {
            when (b) {
                is PageBreak -> { newPage(); prevAfter = 0f }
                is Paragraph -> {
                    if (b.style.pageBreakBefore && y > ps.marginTop + 1) newPage()
                    val before = if (y <= ps.marginTop + 0.5f) 0f else max(b.style.spaceBefore - prevAfter * 0f, 0f)
                    val lines = layoutParagraph(b, ctx.right - ctx.left)
                    y += before
                    // Keep a heading with at least its first line + next block start.
                    // Keep a heading with the start of what follows (Word: keep with next + 2-line widow control).
                    val next = sec.blocks.getOrNull(bi + 1)
                    val nextStart = when (next) {
                        is Paragraph -> b.style.spaceAfter + next.style.spaceBefore + layoutParagraph(next, ctx.right - ctx.left).take(2).sumOf { it.height.toDouble() }.toFloat()
                        is Table -> b.style.spaceAfter + 30f
                        else -> 0f
                    }
                    // Hand-made headings (one bold line) are kept with what follows too.
                    val headingLike = b.style.keepWithNext || (lines.size == 1 && next is Paragraph &&
                        b.inlines.filterIsInstance<TextRun>().filter { it.text.isNotBlank() }.let { r -> r.isNotEmpty() && r.all { it.style.bold } })
                    if (headingLike && y + lines.sumOf { it.height.toDouble() }.toFloat() + nextStart > bottom && y > ps.marginTop + 1) newPage()
                    if (b.style.shading != null) {
                        val h = lines.sumOf { it.height.toDouble() }.toFloat()
                        page.ops.add(RectOp(ctx.left + b.style.indentLeft, y, ctx.right - ctx.left - b.style.indentLeft - b.style.indentRight, h, b.style.shading, null))
                    }
                    val floats = b.inlines.filterIsInstance<FloatingImage>()
                    if (floats.isNotEmpty() && lines.isNotEmpty() && y + lines[0].height > bottom && y > ps.marginTop + 1) newPage()
                    for (f in floats) placeFloat(page, ps, f, y)
                    for (ln in lines) {
                        if (y + ln.height > bottom && y > ps.marginTop + 1) newPage()
                        emitLine(page, ln, ctx.left, y, 0, 0)
                        y += ln.height
                    }
                    b.style.borderBottom?.let { c -> page.ops.add(LineOp(ctx.left + b.style.indentLeft, y + 1f, ctx.right - b.style.indentRight, y + 1f, c, b.style.borderWidth)) }
                    y += b.style.spaceAfter
                    prevAfter = b.style.spaceAfter
                }
                is Table -> {
                    val width = ctx.right - ctx.left
                    val cols = scaleColumns(b.columnWidths, width, b.rows.maxOfOrNull { r -> r.cells.sumOf { it.span } } ?: 1)
                    val headerRows = b.rows.takeWhile { it.header }
                    for ((ri, row) in b.rows.withIndex()) {
                        val laid = layoutRow(row, cols)
                        if (y + laid.height > bottom && y > ps.marginTop + 1) {
                            newPage()
                            // Repeat header rows on the new page.
                            if (ri >= headerRows.size) for (h in headerRows) { val lh = layoutRow(h, cols); drawRow(page, lh, ctx.left, y, b.borders); y += lh.height }
                        }
                        drawRow(page, laid, ctx.left, y, b.borders)
                        y += laid.height
                    }
                    y += 8f
                    prevAfter = 8f
                }
            }
        }
    }

    private fun placeFloat(page: RenderPage, ps: PageSetup, f: FloatingImage, paraY: Float) {
        val w = min(f.image.widthPt, ps.width); val h = min(f.image.heightPt, ps.height)
        val (x0, x1) = if (f.hRel == "page") 0f to ps.width else ps.marginLeft to ps.width - ps.marginRight
        val x = when (f.hAlign) { "left", "inside" -> x0; "center" -> (x0 + x1 - w) / 2; "right", "outside" -> x1 - w; else -> x0 + f.hOffset }
        val (y0, y1) = when (f.vRel) { "page" -> 0f to ps.height; "margin", "topMargin" -> ps.marginTop to ps.height - ps.marginBottom; else -> paraY to paraY }
        val y = when (f.vAlign) { "top" -> y0; "center" -> (y0 + y1 - h) / 2; "bottom" -> y1 - h; else -> y0 + f.vOffset }
        page.ops.add(ImageOp(x.coerceIn(0f, ps.width - w), y.coerceIn(0f, ps.height - h), w, h, f.image))
    }

    // ---------------------------------------------------------------- paragraphs

    class Piece(val text: String, val style: CharStyle, val width: Float, val image: ImageRun? = null, val field: String? = null)
    class Line(val pieces: List<Piece>, val x: Float, val width: Float, val ascent: Float, val height: Float, val justify: Float, val label: Piece?, val labelX: Float? = null)

    fun layoutParagraph(p: Paragraph, width: Float, page: Int = 0, total: Int = 0): List<Line> {
        val st = p.style
        // Break inlines into words (keeping trailing spaces) and images.
        val words = ArrayList<Piece>()
        var lineBreaks = HashSet<Int>()
        for (inl in p.inlines) {
            when (inl) {
                is TextRun -> {
                    val t = inl.text
                    var i = 0
                    while (i < t.length) {
                        if (t[i] == '\n') { lineBreaks.add(words.size); i++; continue }
                        if (t[i] == '\t') { words.add(Piece("    ", inl.style, w("    ", inl.style))); i++; continue }
                        var j = i
                        while (j < t.length && t[j] != ' ' && t[j] != '\n' && t[j] != '\t') j++
                        while (j < t.length && t[j] == ' ') j++
                        val s = t.substring(i, j)
                        words.add(Piece(s, inl.style, w(s, inl.style))); i = j
                    }
                }
                is FloatingImage -> {} // drawn by the flow at its own position
                is ImageRun -> {
                    val maxW = width - st.indentLeft - st.indentRight
                    val scale = min(1f, maxW / inl.widthPt)
                    words.add(Piece("", CharStyle(), inl.widthPt * scale, ImageRun(inl.data, inl.widthPt * scale, inl.heightPt * scale, inl.mime)))
                }
                is FieldRun -> {
                    val s = if (inl.kind == "NUMPAGES") total.toString() else page.toString()
                    words.add(Piece(s, inl.style, w(s.ifEmpty { "0" }, inl.style), field = inl.kind))
                }
            }
        }
        val defaultStyle = (p.inlines.firstOrNull { it is TextRun } as? TextRun)?.style ?: CharStyle()
        val label = st.listLabel?.let { Piece(it, defaultStyle, w(it + " ", defaultStyle)) }
        val lines = ArrayList<Line>()
        // With a list label, the label hangs at indentLeft+firstLine and text starts at indentLeft.
        val left0 = if (label != null) st.indentLeft else st.indentLeft + st.firstLine
        var cur = ArrayList<Piece>(); var curW = 0f
        var first = true
        fun finish(last: Boolean) {
            val left = if (first) left0 else st.indentLeft
            val avail = width - left - st.indentRight
            // Trim trailing spaces for width/alignment.
            if (cur.isNotEmpty()) {
                val lp = cur.last()
                val t = lp.text.trimEnd()
                if (t != lp.text && lp.image == null) { cur[cur.size - 1] = Piece(t, lp.style, w(t, lp.style), field = lp.field); }
            }
            val lw = cur.sumOf { it.width.toDouble() }.toFloat()
            val baseSize = cur.maxOfOrNull { if (it.image != null) 0f else it.style.size } ?: defaultStyle.size
            val imgH = cur.maxOfOrNull { it.image?.heightPt ?: 0f } ?: 0f
            val ascent = max(baseSize * 0.92f, imgH)
            val height = max(baseSize, defaultStyle.size) * 1.2f * st.lineSpacing + max(0f, imgH - baseSize)
            val x = when (st.align) {
                HAlign.CENTER -> left + (avail - lw) / 2
                HAlign.RIGHT -> left + avail - lw
                else -> left
            }
            val spaces = cur.dropLast(1).count { it.text.endsWith(" ") }
            val justify = if (st.align == HAlign.JUSTIFY && !last && spaces > 0) (avail - lw) / spaces else 0f
            lines.add(Line(cur, max(0f, x), lw, ascent, if (cur.isEmpty() && imgH == 0f) (if (p.inlines.none { it is TextRun }) st.markSize ?: defaultStyle.size else defaultStyle.size) * 1.2f * st.lineSpacing else height, justify,
                if (first) label else null, if (first && label != null) max(0f, st.indentLeft + st.firstLine) else null))
            cur = ArrayList(); curW = 0f; first = false
        }
        for ((idx, wd) in words.withIndex()) {
            if (idx in lineBreaks && (cur.isNotEmpty() || idx > 0)) finish(true)
            val left = if (first) left0 else st.indentLeft
            val avail = width - left - st.indentRight
            val visible = if (wd.image != null) wd.width else w(wd.text.trimEnd(), wd.style)
            if (cur.isNotEmpty() && curW + visible > avail) finish(false)
            if (cur.isEmpty() && visible > avail && wd.image == null && wd.text.length > 1) {
                // Break a very long word.
                var rest = wd.text
                while (rest.isNotEmpty()) {
                    var k = rest.length
                    while (k > 1 && w(rest.substring(0, k), wd.style) > avail) k--
                    cur.add(Piece(rest.substring(0, k), wd.style, w(rest.substring(0, k), wd.style)))
                    rest = rest.substring(k)
                    if (rest.isNotEmpty()) finish(false)
                }
                curW = cur.sumOf { it.width.toDouble() }.toFloat()
                continue
            }
            cur.add(wd); curW += wd.width
        }
        if (words.size in lineBreaks) finish(true)
        finish(true)
        return lines
    }

    private fun emitLine(page: RenderPage, ln: Line, left: Float, top: Float, pageNo: Int, total: Int) {
        val baseline = top + ln.ascent + (ln.height - ln.ascent * 1.2f).coerceAtLeast(0f) * 0.35f
        ln.label?.let { lb ->
            val lx = if (ln.labelX != null) left + ln.labelX else left + ln.x - lb.width
            page.ops.add(TextOp(lx, baseline, lb.text, lb.style, lb.width))
        }
        var x = left + ln.x
        for ((i, p) in ln.pieces.withIndex()) {
            if (p.image != null) {
                page.ops.add(ImageOp(x, baseline - p.image.heightPt, p.image.widthPt, p.image.heightPt, p.image))
            } else if (p.text.isNotEmpty()) {
                p.style.highlight?.let { page.ops.add(RectOp(x, baseline - p.style.size * 0.85f, p.width, p.style.size * 1.1f, it, null)) }
                val dy = when (p.style.vertAlign) { 1 -> -p.style.size * 0.35f; -1 -> p.style.size * 0.15f; else -> 0f }
                val text = p.text.trimEnd()
                if (text.isNotEmpty()) page.ops.add(TextOp(x, baseline + dy, text, p.style.copy(size = effSize(p.style), vertAlign = 0), w(text, p.style)))
                if (p.style.underline) page.ops.add(LineOp(x, baseline + 1.5f, x + w(text, p.style), baseline + 1.5f, p.style.color, max(0.5f, p.style.size / 18)))
                if (p.style.strike) page.ops.add(LineOp(x, baseline - p.style.size * 0.3f, x + w(text, p.style), baseline - p.style.size * 0.3f, p.style.color, max(0.5f, p.style.size / 18)))
            }
            x += p.width
            if (ln.justify > 0 && i < ln.pieces.size - 1 && p.text.endsWith(" ")) x += ln.justify
        }
    }

    // ---------------------------------------------------------------- tables

    private class LaidCell(val x: Float, val w: Float, val content: List<Pair<Any, Float>>, val height: Float, val shading: Int?)
    private class LaidRow(val cells: List<LaidCell>, val height: Float)

    private fun scaleColumns(widths: List<Float>, total: Float, count: Int): List<Float> {
        val ws = if (widths.size >= count && widths.all { it > 0 }) widths.take(count) else List(count) { total / count }
        val sum = ws.sum()
        return ws.map { it * total / sum }
    }

    // Word's default cell margins: 0.08" left/right, none top/bottom.
    private val cellPad = 5.4f
    private val cellPadV = 0.5f

    private fun layoutRow(row: Row, cols: List<Float>): LaidRow {
        var col = 0
        var x = 0f
        val cells = ArrayList<LaidCell>()
        for (c in row.cells) {
            val span = c.span.coerceAtLeast(1)
            val cw = cols.subList(min(col, cols.size - 1), min(cols.size, col + span)).sum().takeIf { it > 0 } ?: cols.last()
            val inner = cw - 2 * cellPad
            val content = ArrayList<Pair<Any, Float>>()
            var h = cellPadV
            for (b in c.blocks) {
                when (b) {
                    is Paragraph -> {
                        val lines = layoutParagraph(b, inner)
                        for (ln in lines) { content.add(ln to h); h += ln.height }
                        if (b !== c.blocks.last()) h += min(b.style.spaceAfter, 4f)
                    }
                    is Table -> {
                        // Nested tables: flatten to text lines.
                        val txt = Paragraph(listOf(TextRun(b.rows.joinToString("\n") { r -> r.cells.joinToString(" | ") { it.blocks.filterIsInstance<Paragraph>().joinToString(" ") { p -> p.text } } }, CharStyle(size = 9f))))
                        for (ln in layoutParagraph(txt, inner)) { content.add(ln to h); h += ln.height }
                    }
                    else -> {}
                }
            }
            h += cellPadV
            cells.add(LaidCell(x, cw, content, h, c.shading))
            x += cw; col += span
        }
        return LaidRow(cells, cells.maxOfOrNull { it.height } ?: 14f)
    }

    private fun drawRow(page: RenderPage, row: LaidRow, left: Float, top: Float, borders: Boolean) {
        for (c in row.cells) {
            if (c.shading != null) page.ops.add(RectOp(left + c.x, top, c.w, row.height, c.shading, null))
            if (borders) page.ops.add(RectOp(left + c.x, top, c.w, row.height, null, 0xFF7F7F7F.toInt(), 0.5f))
            for ((ln, y) in c.content) emitLine(page, ln as Line, left + c.x + cellPad, top + y, 0, 0)
        }
    }

    // ---------------------------------------------------------------- headers/footers, fixed pages

    private fun measureBlock(b: BlockNode, width: Float): Float = when (b) {
        is Paragraph -> layoutParagraph(b, width).sumOf { it.height.toDouble() }.toFloat() + b.style.spaceAfter
        else -> 0f
    }

    private fun drawStatic(page: RenderPage, b: BlockNode, ctx: Ctx, y0: Float, pageNo: Int, total: Int): Float {
        var y = y0
        if (b is Paragraph) {
            for (ln in layoutParagraph(b, ctx.right - ctx.left, pageNo, total)) { emitLine(page, ln, ctx.left, y, pageNo, total); y += ln.height }
            y += b.style.spaceAfter
        }
        return y
    }

    private fun fixed(ps: PageSetup, fp: FixedPage): RenderPage {
        val page = RenderPage(ps.width, ps.height)
        fp.background?.let { page.ops.add(RectOp(0f, 0f, ps.width, ps.height, it, null)) }
        for ((img, r) in fp.images) page.ops.add(ImageOp(r[0], r[1], r[2], r[3], img))
        for (f in fp.frames) {
            f.fill?.let { page.ops.add(RectOp(f.x, f.y, f.w, f.h, it, null)) }
            val inner = f.w - 14f
            // Shrink text to fit the frame (PowerPoint's "shrink text on overflow").
            var scale = 1f
            var laid: List<Pair<Line, Float>>
            var deferred: ArrayList<() -> Unit>
            while (true) {
                laid = ArrayList()
                deferred = ArrayList()
                var y = 4f
                for (b in f.blocks) {
                    when (b) {
                        is Paragraph -> {
                            val sp = if (scale == 1f) b else Paragraph(b.inlines.map { if (it is TextRun) TextRun(it.text, it.style.copy(size = it.style.size * scale)) else it }, b.style)
                            y += b.style.spaceBefore * scale
                            for (ln in layoutParagraph(sp, inner)) { laid.add(ln to y); y += ln.height }
                            y += b.style.spaceAfter * scale
                        }
                        is Table -> {
                            val cols = scaleColumns(b.columnWidths, f.w, b.rows.maxOfOrNull { r -> r.cells.sumOf { it.span } } ?: 1)
                            var ty = f.y + y
                            for (row in b.rows) { val lr = layoutRow(row, cols); val at = ty; deferred.add { drawRow(page, lr, f.x, at, b.borders) }; ty += lr.height }
                            y = ty - f.y
                        }
                        else -> {}
                    }
                }
                if (y <= f.h + 4 || scale < 0.55f) {
                    val off = if (f.anchorMiddle) max(0f, (f.h - y) / 2) else 0f
                    deferred.forEach { it() }
                    for ((ln, ly) in laid) emitLine(page, ln, f.x + 7f, f.y + ly + off, 0, 0)
                    break
                }
                scale *= 0.92f
            }
        }
        return page
    }
}
