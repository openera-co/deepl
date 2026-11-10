package app.carnet.core.layout

import app.carnet.core.doc.Region
import app.carnet.core.doc.RunText
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shared layout logic for "translate in place" on PDFs and photos:
 * positioned lines → paragraph blocks (with inline styles) → translated, styled text fitted back.
 * Coordinates: origin top-left, y grows downwards, units are the caller's (PDF points or pixels).
 */
data class Style(val bold: Boolean = false, val italic: Boolean = false, val color: Int = 0xFF000000.toInt(), val fontName: String = "")

data class StyledRun(val text: String, val style: Style)

data class TextLine(
    val page: Int,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val text: String,
    val fontSize: Float,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val color: Int = 0xFF000000.toInt(),
    val fontName: String = "",
    val runs: List<StyledRun> = listOf(StyledRun(text, Style(bold, italic, color, fontName))),
    /** Text direction in degrees (0 = normal; 90/180/270 for rotated text such as chart axis titles). Coordinates are in that direction's frame. */
    val rotation: Int = 0,
) {
    val height get() = bottom - top
    val width get() = right - left
}

enum class Align { LEFT, CENTER, RIGHT, JUSTIFY }

/** A drawn straight rule (table border, underline, box edge): horizontal at [pos] from [from] to [to], or vertical. */
class Rule(val pos: Float, val from: Float, val to: Float)

/** Rules drawn on one page, in the same top-left coordinates as the text. */
class Rules(val horizontal: List<Rule>, val vertical: List<Rule>) {
    /** A vertical rule between x0 and x1 crossing the band y0..y1. */
    fun verticalBetween(x0: Float, x1: Float, y0: Float, y1: Float) = vertical.any { it.pos > x0 && it.pos < x1 && it.from < y1 - 0.5f && it.to > y0 + 0.5f }
    /** A horizontal rule between y0 and y1 under the span x0..x1. */
    fun horizontalBetween(y0: Float, y1: Float, x0: Float, x1: Float) = horizontal.any { it.pos > y0 && it.pos < y1 && min(it.to, x1) - max(it.from, x0) > 0.5f * (x1 - x0).coerceAtLeast(1f) }
    companion object { val NONE = Rules(emptyList(), emptyList()) }
}

class Block(
    val page: Int,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val lines: List<TextLine>,
    val text: String,
    val fontSize: Float,
    val lineHeight: Float,
    val align: Align,
    /** Distinct styles used in the block; [runs] index into it. */
    val styles: List<Style>,
    val runs: List<RunText>,
) {
    val width get() = right - left
    val height get() = bottom - top
    /** Dominant style (most characters). */
    val style: Style get() = styles[runs.groupBy { it.format }.maxBy { e -> e.value.sumOf { it.text.length } }.key]
    val bold get() = style.bold
    val italic get() = style.italic
    val color get() = style.color
    val fontName get() = style.fontName
    /** Lowest y the block may grow to (set by [Layout.assignRoom]). */
    var maxBottom: Float = bottom
    /** Rightmost x a single-line block may extend to (headings, labels). */
    var maxRight: Float = right
    var minLeft: Float = left
    /** The page edge: text may never go below it. */
    var hardBottom: Float = Float.MAX_VALUE
    /** Offset of the first line from [left] (first-line indent or bullet of a hanging indent). */
    val firstIndent: Float get() = if (lines.size < 2) 0f else lines[0].left - left
    /** Offset of the following lines from [left] (hanging indent under a bullet's text). */
    val restIndent: Float get() = if (lines.size < 2) 0f else lines.drop(1).minOf { it.left } - left
}

data class Segment(val text: String, val style: Style, val x: Float, val width: Float)
data class PlacedLine(val segments: List<Segment>, val baseline: Float) {
    val text get() = segments.joinToString("") { it.text }
}
data class Placement(val block: Block, val fontSize: Float, val lines: List<PlacedLine>)

/** Measures rendered text width at a font size for a style (font chosen by the platform). */
fun interface TextMeasurer {
    fun width(text: String, size: Float, style: Style): Float
}

object Layout {

    /** Groups lines (already in reading order per page) into paragraph blocks. [sizeTolerance]: 0.12 for PDFs, ~0.3 for OCR. */
    fun blocks(lines: List<TextLine>, sizeTolerance: Float = 0.12f, rules: (Int) -> Rules = { Rules.NONE }): List<Block> {
        val out = ArrayList<Block>()
        var cur = ArrayList<TextLine>()
        fun flush() { if (cur.isNotEmpty()) { out.add(makeBlock(cur)); cur = ArrayList() } }
        // Line pitch inside each enclosing box (table cell, card, region between rules) per font size:
        // the tightest pitch that occurs there is the line spacing; a larger pitch means paragraph spacing.
        fun containerOf(l: TextLine): String {
            val rs = rules(l.page)
            val left = rs.vertical.filter { it.pos <= l.left + 1 && it.from < l.bottom && it.to > l.top }.maxOfOrNull { it.pos }
            val right = rs.vertical.filter { it.pos >= l.right - 1 && it.from < l.bottom && it.to > l.top }.minOfOrNull { it.pos }
            val top = rs.horizontal.filter { it.pos <= l.top + 1 && it.from < l.right && it.to > l.left }.maxOfOrNull { it.pos }
            val bottom = rs.horizontal.filter { it.pos >= l.bottom - 1 && it.from < l.right && it.to > l.left }.minOfOrNull { it.pos }
            return "${l.page}:${left?.toInt()}:${right?.toInt()}:${top?.toInt()}:${bottom?.toInt()}:${(l.fontSize * 2).toInt()}"
        }
        val nonBlank = lines.filter { it.text.isNotBlank() }
        val containerOfLine = HashMap<TextLine, String>()
        for (l in nonBlank) containerOfLine[l] = containerOf(l)
        // Right edge of the text column each line belongs to: a high percentile of the right edges
        // of lines in the same box that start at the same x (bounded by a rule on the right).
        val byContainer = nonBlank.groupBy { containerOfLine[it]!!.substringBeforeLast(':') }
        val colRightOf = HashMap<TextLine, Float>()
        for ((_, ls) in byContainer) for (l in ls) {
            val rights = ls.filter { abs(it.left - l.left) < 2f && abs(it.fontSize - l.fontSize) < 0.2f * l.fontSize }.map { it.right }.sorted()
            val ruleRight = rules(l.page).vertical.filter { it.pos >= l.right - 1 && it.from < l.bottom && it.to > l.top }.minOfOrNull { it.pos - 0.3f * l.fontSize }
            val col = if (rights.size >= 4) rights[(rights.size * 85) / 100] else l.right
            colRightOf[l] = if (ruleRight != null) max(l.right, min(col, ruleRight)) else col
        }
        val pitchVotes = HashMap<String, ArrayList<Float>>()
        for ((a, b) in nonBlank.zipWithNext()) {
            if (a.page != b.page || a.rotation != b.rotation || abs(a.fontSize - b.fontSize) > 0.1f * a.fontSize || abs(a.left - b.left) > 0.8f * a.fontSize) continue
            if (containerOfLine[a] != containerOfLine[b]) continue
            val pitch = b.top - a.top
            if (pitch <= 0.9f * a.fontSize || pitch > 2.2f * a.fontSize) continue
            pitchVotes.getOrPut(containerOfLine[a]!!) { ArrayList() }.add(pitch)
        }
        val usualPitch = pitchVotes.mapValues { (_, v) -> v.sorted()[v.size / 10] }
        for (l in lines) {
            if (l.text.isBlank()) continue
            val prev = cur.lastOrNull()
            if (prev == null) { cur.add(l); continue }
            val size = max(prev.fontSize, l.fontSize)
            val gap = l.top - prev.bottom
            // OCR'd line boxes hug the glyphs, so a line without descenders looks ~20% smaller: be tolerant.
            val sameSize = abs(prev.fontSize - l.fontSize) <= sizeTolerance * size
            val overlapX = min(prev.right, l.right) - max(prev.left, l.left)
            val aligned = abs(prev.left - l.left) <= 0.8f * size || overlapX > 0.5f * min(prev.width, l.width)
            val close = l.page == prev.page && l.rotation == prev.rotation && gap >= -0.4f * size && gap <= 0.75f * size
            val blockRight = cur.maxOf { it.right }; val blockLeft = cur.minOf { it.left }
            val prevEnds = prev.text.trimEnd().lastOrNull()?.let { it in ".!?:»" } == true && prev.right < blockLeft + 0.8f * (blockRight - blockLeft) && cur.size > 0 && prev.width < 0.8f * max(blockRight - blockLeft, l.width)
            // Word-wrap invariant: a left-aligned line only ends early when the next word doesn't fit.
            // If the next line's first word would have fitted after it, the previous line ended a paragraph.
            val colRight = maxOf(blockRight, l.right, colRightOf[prev] ?: 0f)
            val firstWord = l.text.trimStart().substringBefore(' ').length
            val leftAligned = abs(prev.left - l.left) <= 0.3f * size || abs(prev.left - blockLeft) <= 0.3f * size
            val endedEarly = leftAligned && colRight - prev.right > (firstWord + 1) * 0.62f * size + 0.5f * size
            // A dash/bullet at the start of a line inside a full justified line is a continuation, not a list item.
            val prevFull = colRight - prev.right < 0.6f * size && prev.text.trimEnd().lastOrNull()?.let { it in ".:;!?" } != true
            val listStart = isListItem(l.text) && !prevFull
            val usual = if (containerOfLine[prev] == containerOfLine[l]) usualPitch[containerOfLine[prev]] else null
            val spaced = usual != null && l.top - prev.top > usual * 1.08f + 0.5f
            // Table borders and box edges separate text.
            val rs = rules(l.page)
            val ruled = rs.horizontalBetween(prev.bottom - 0.3f * size, l.top + 0.3f * size, max(prev.left, l.left), min(prev.right, l.right).coerceAtLeast(max(prev.left, l.left) + 1f))
            if (close && sameSize && aligned && !prevEnds && !endedEarly && !listStart && !spaced && !ruled) cur.add(l) else { flush(); cur.add(l) }
        }
        flush()
        return out
    }

    private fun isListItem(t: String) = Regex("^\\s*([•●◦▪■–—\\-*·]|\\d{1,3}[.)]|[a-z][.)])\\s").containsMatchIn(t)

    private fun makeBlock(ls: List<TextLine>): Block {
        val left = ls.minOf { it.left }; val right = ls.maxOf { it.right }
        val top = ls.minOf { it.top }; val bottom = ls.maxOf { it.bottom }
        val styles = ArrayList<Style>()
        val runs = ArrayList<RunText>()
        fun add(text: String, st: Style) {
            if (text.isEmpty()) return
            var idx = styles.indexOf(st)
            if (idx < 0) { styles.add(st); idx = styles.size - 1 }
            val last = runs.lastOrNull()
            if (last != null && last.format == idx) runs[runs.size - 1] = RunText(idx, last.text + text) else runs.add(RunText(idx, text))
        }
        for ((i, l) in ls.withIndex()) {
            val lineRuns = l.runs.filter { it.text.isNotEmpty() }
            if (i > 0) {
                val prevText = runs.lastOrNull()?.text ?: ""
                val firstChar = lineRuns.firstOrNull()?.text?.trimStart()?.firstOrNull()
                if (prevText.endsWith("-") && prevText.length > 1 && prevText[prevText.length - 2].isLowerCase() && firstChar?.isLowerCase() == true) {
                    // De-hyphenate words split across lines ("photo-" + "synthèse").
                    runs[runs.size - 1] = RunText(runs.last().format, prevText.dropLast(1))
                } else add(" ", lineRuns.firstOrNull()?.style ?: styles.firstOrNull() ?: Style())
            }
            lineRuns.forEachIndexed { k, r ->
                var t = r.text
                if (k == 0) t = t.trimStart()
                if (k == lineRuns.size - 1) t = t.trimEnd()
                add(t, r.style)
            }
        }
        val text = runs.joinToString("") { it.text }
        val sizes = ls.map { it.fontSize }.sorted()
        val lh = if (ls.size > 1) (ls.zipWithNext { a, b -> b.top - a.top }.sorted().let { it[it.size / 2] }) else sizes[sizes.size / 2] * 1.2f
        val w = right - left
        val align = when {
            ls.size >= 3 && ls.dropLast(1).all { abs(it.right - right) < 0.01f * w + 1 } && ls.drop(1).all { abs(it.left - ls[1].left) < 2 } -> Align.JUSTIFY
            ls.all { abs((it.left + it.right) / 2 - (left + right) / 2) < 0.03f * w + 1 } && ls.any { abs(it.left - left) > 3 } -> Align.CENTER
            ls.size > 1 && ls.all { abs(it.right - right) < 2 } && ls.any { abs(it.left - left) > 3 } -> Align.RIGHT
            else -> Align.LEFT
        }
        // Upper-middle size: robust to short lines and to lines without ascenders/descenders.
        val size = sizes[(sizes.size * 3) / 4]
        return Block(ls.first().page, left, top, right, bottom, ls, text, size, lh.coerceAtLeast(sizes[0]), align, styles, runs)
    }

    /**
     * Computes how far each block may grow downwards without touching the next block below it
     * (or the page bottom margin), so longer translations reflow instead of shrinking.
     */
    fun assignRoom(blocks: List<Block>, pageBottom: (Int) -> Float, pageRight: (Int) -> Float = { Float.MAX_VALUE }, rules: (Int) -> Rules = { Rules.NONE }) {
        val byPage = blocks.groupBy { it.page to it.lines.first().rotation }
        for (b in blocks) {
            val same = byPage[b.page to b.lines.first().rotation]!!
            if (b.lines.first().rotation != 0) {
                // Rotated labels (axis titles): keep to their own box.
                b.maxBottom = b.bottom; b.hardBottom = b.bottom + b.lineHeight; b.maxRight = b.right; b.minLeft = b.left
                continue
            }
            var limit = pageBottom(b.page)
            for (o in same) {
                if (o === b || o.top < b.bottom - 0.5f) continue
                val overlap = min(o.right, b.right) - max(o.left, b.left)
                if (overlap > 0) limit = min(limit, o.top - b.lineHeight * 0.25f)
            }
            val rs = rules(b.page)
            // Never grow through a rule below (cell border, box edge, heading underline).
            for (r in rs.horizontal) if (r.pos >= b.bottom - 0.5f && r.pos < limit && min(r.to, b.right) - max(r.from, b.left) > 0.3f * b.width) limit = r.pos - 1f
            b.maxBottom = max(b.bottom, limit)
            b.hardBottom = max(b.bottom, pageBottom(b.page))
            // Horizontal room: up to the text column's right edge (or the page margin), stopping at blocks beside it.
            val pageLeft = same.minOf { it.left }
            val columnRight = max(b.right, min(same.maxOf { it.right }, pageRight(b.page) - pageLeft))
            var right = columnRight
            var left = pageLeft
            for (o in same) {
                if (o === b) continue
                val vOverlap = min(o.bottom, b.bottom) - max(o.top, b.top)
                if (vOverlap <= 0) continue
                if (o.left >= b.right - 0.5f) right = min(right, o.left - b.fontSize * 0.6f)
                if (o.right <= b.left + 0.5f) left = max(left, o.right + b.fontSize * 0.6f)
            }
            for (r in rs.vertical) if (r.from < b.bottom && r.to > b.top) {
                if (r.pos >= b.right - 0.5f) right = min(right, r.pos - b.fontSize * 0.3f)
                if (r.pos <= b.left + 0.5f) left = max(left, r.pos + b.fontSize * 0.3f)
            }
            // Never beyond the page, even if the source text was cut off at the edge.
            b.maxRight = min(max(b.right, right), pageRight(b.page))
            b.minLeft = max(0f, min(b.left, left))
        }
    }

    /**
     * Wraps styled [regions] into the block's width. Uses the original height first, then the free
     * room below the block, and only then shrinks the font (down to [minScale]).
     */
    fun fit(block: Block, regions: List<Region>, m: TextMeasurer, minScale: Float = 0.6f, maxSize: Float? = null): Placement {
        val words = toWords(block, regions)
        val single = block.lines.size == 1
        val (left, right) = when {
            !single -> block.left to min(block.right, block.maxRight)
            block.align == Align.CENTER -> {
                val c = (block.left + block.right) / 2; val half = min(c - block.minLeft, block.maxRight - c)
                (c - half) to (c + half)
            }
            block.align == Align.RIGHT -> block.minLeft to block.right
            else -> block.left to block.maxRight
        }
        val maxW = max(right - left, 4f)
        val ind0 = block.firstIndent.coerceIn(0f, maxW / 3); val ind1 = block.restIndent.coerceIn(0f, maxW / 3)
        val widthAt = { i: Int -> max(maxW - if (i == 0) ind0 else ind1, 4f) }
        val ratio = block.lineHeight / block.fontSize.coerceAtLeast(1f)
        val room = block.maxBottom - block.top
        var size = maxSize?.let { min(it, block.fontSize) } ?: block.fontSize
        var lines = wrap(words, widthAt, size, m)
        // Shrink to fit the room; below minScale only if the text would otherwise leave the page.
        while (size > block.fontSize * 0.4f) {
            val h = (lines.size - 1) * size * ratio + size
            if (h <= max(block.height, room) + 0.5f) break
            if (size <= block.fontSize * minScale && h <= room + block.lineHeight * 1.5f && block.top + h <= block.hardBottom) break
            size *= 0.95f
            lines = wrap(words, widthAt, size, m)
        }
        val lh = size * ratio
        val ascent = size * 0.8f
        val firstBaselineOffset = (block.lines.first().height * 0.78f).coerceAtLeast(ascent * 0.9f).coerceAtMost(ascent * 1.2f)
        val placed = lines.mapIndexed { i, ws ->
            val w = ws.sumOf { it.width.toDouble() }.toFloat()
            val lw = widthAt(i)
            val startX = when (block.align) {
                Align.CENTER -> left + (maxW - w) / 2
                Align.RIGHT -> right - w
                else -> left + (if (i == 0) ind0 else ind1)
            }
            var x = startX
            val segs = ArrayList<Segment>()
            // Justified paragraphs: spread the free space over the gaps, except on the last line.
            val gaps = ws.count { it.text.endsWith(" ") }.let { if (ws.lastOrNull()?.text?.endsWith(" ") == true) it - 1 else it }
            val justify = block.align == Align.JUSTIFY && i < lines.size - 1 && gaps > 0 && lw - w < lw * 0.35f
            val extra = if (justify) (lw - w) / gaps else 0f
            for ((k, wd) in ws.withIndex()) {
                segs.add(Segment(wd.text, wd.style, x, wd.width)); x += wd.width
                if (justify && k < ws.size - 1 && wd.text.endsWith(" ")) x += extra
            }
            PlacedLine(if (justify) mergeAdjacent(segs) else merge(segs), block.top + firstBaselineOffset + i * lh)
        }
        return Placement(block, size, placed)
    }

    /** Plain (single-style) fit, used by photo overlays. */
    fun fit(block: Block, text: String, m: TextMeasurer, minScale: Float = 0.6f, maxSize: Float? = null): Placement = fit(block, listOf(Region(styleIndex(block), text)), m, minScale, maxSize)

    private fun styleIndex(b: Block) = b.styles.indexOf(b.style).coerceAtLeast(0)

    private class Word(val text: String, val style: Style, var width: Float = 0f)

    private fun toWords(block: Block, regions: List<Region>): List<Word> {
        val out = ArrayList<Word>()
        for (r in regions) {
            val st = block.styles.getOrElse(r.format) { block.style }
            // Split keeping the trailing space with each word.
            var i = 0
            val t = r.text.replace('\n', ' ').replace('\t', ' ')
            while (i < t.length) {
                var j = i
                while (j < t.length && t[j] != ' ') j++
                while (j < t.length && t[j] == ' ') j++
                out.add(Word(t.substring(i, j), st)); i = j
            }
        }
        return out
    }

    private fun wrap(words: List<Word>, widthAt: (Int) -> Float, size: Float, m: TextMeasurer): List<List<Word>> {
        val lines = ArrayList<List<Word>>()
        var cur = ArrayList<Word>(); var w = 0f
        for (word in words) {
            val maxW = widthAt(lines.size)
            val ww = m.width(word.text, size, word.style)
            val visible = m.width(word.text.trimEnd(), size, word.style)
            if (cur.isNotEmpty() && w + visible > maxW) {
                lines.add(trimLine(cur, size, m)); cur = ArrayList(); w = 0f
            }
            if (cur.isEmpty() && visible > maxW) {
                // A word longer than the line: break it by characters.
                var rest = word.text
                while (rest.isNotEmpty()) {
                    var k = rest.length
                    while (k > 1 && m.width(rest.substring(0, k), size, word.style) > maxW) k--
                    val piece = Word(rest.substring(0, k), word.style, m.width(rest.substring(0, k), size, word.style))
                    rest = rest.substring(k)
                    if (rest.isNotEmpty()) lines.add(listOf(piece)) else { cur.add(piece); w = piece.width }
                }
                continue
            }
            cur.add(Word(word.text, word.style, ww)); w += ww
        }
        if (cur.isNotEmpty()) lines.add(trimLine(cur, size, m))
        return lines.ifEmpty { listOf(emptyList()) }
    }

    private fun trimLine(ws: List<Word>, size: Float, m: TextMeasurer): List<Word> {
        if (ws.isEmpty()) return ws
        val last = ws.last()
        val t = last.text.trimEnd()
        return if (t == last.text) ws else ws.dropLast(1) + Word(t, last.style, m.width(t, size, last.style))
    }

    /** Merges only segments that touch (justified lines keep one segment per word). */
    private fun mergeAdjacent(segs: List<Segment>): List<Segment> {
        val out = ArrayList<Segment>()
        for (s in segs) {
            val l = out.lastOrNull()
            if (l != null && l.style == s.style && abs(l.x + l.width - s.x) < 0.01f) out[out.size - 1] = Segment(l.text + s.text, l.style, l.x, l.width + s.width) else out.add(s)
        }
        return out
    }

    private fun merge(segs: List<Segment>): List<Segment> {
        val out = ArrayList<Segment>()
        for (s in segs) {
            val l = out.lastOrNull()
            if (l != null && l.style == s.style) out[out.size - 1] = Segment(l.text + s.text, l.style, l.x, l.width + s.width) else out.add(s)
        }
        return out
    }
}
