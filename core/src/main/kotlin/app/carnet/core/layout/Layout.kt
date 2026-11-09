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
) {
    val height get() = bottom - top
    val width get() = right - left
}

enum class Align { LEFT, CENTER, RIGHT, JUSTIFY }

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

    /** Groups lines (already in reading order per page) into paragraph blocks. */
    fun blocks(lines: List<TextLine>): List<Block> {
        val out = ArrayList<Block>()
        var cur = ArrayList<TextLine>()
        fun flush() { if (cur.isNotEmpty()) { out.add(makeBlock(cur)); cur = ArrayList() } }
        for (l in lines) {
            if (l.text.isBlank()) continue
            val prev = cur.lastOrNull()
            if (prev == null) { cur.add(l); continue }
            val size = max(prev.fontSize, l.fontSize)
            val gap = l.top - prev.bottom
            val sameSize = abs(prev.fontSize - l.fontSize) <= 0.12f * size
            val overlapX = min(prev.right, l.right) - max(prev.left, l.left)
            val aligned = abs(prev.left - l.left) <= 0.8f * size || overlapX > 0.5f * min(prev.width, l.width)
            val close = l.page == prev.page && gap >= -0.4f * size && gap <= 0.75f * size
            val blockRight = cur.maxOf { it.right }; val blockLeft = cur.minOf { it.left }
            val prevEnds = prev.text.trimEnd().lastOrNull()?.let { it in ".!?:»" } == true && prev.right < blockLeft + 0.8f * (blockRight - blockLeft) && cur.size > 0 && prev.width < 0.8f * max(blockRight - blockLeft, l.width)
            if (close && sameSize && aligned && !prevEnds && !isListItem(l.text)) cur.add(l) else { flush(); cur.add(l) }
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
            lineRuns.forEachIndexed { k, r -> add(if (k == 0) r.text.trimStart() else if (k == lineRuns.size - 1) r.text.trimEnd() else r.text, r.style) }
        }
        val text = runs.joinToString("") { it.text }
        val sizes = ls.map { it.fontSize }.sorted()
        val lh = if (ls.size > 1) (ls.zipWithNext { a, b -> b.top - a.top }.sorted().let { it[it.size / 2] }) else sizes[sizes.size / 2] * 1.2f
        val w = right - left
        val align = when {
            ls.size >= 3 && ls.dropLast(1).all { abs(it.right - right) < 0.02f * w + 1 } && ls.all { abs(it.left - left) < 2 } -> Align.JUSTIFY
            ls.all { abs((it.left + it.right) / 2 - (left + right) / 2) < 0.03f * w + 1 } && ls.any { abs(it.left - left) > 3 } -> Align.CENTER
            ls.size > 1 && ls.all { abs(it.right - right) < 2 } && ls.any { abs(it.left - left) > 3 } -> Align.RIGHT
            else -> Align.LEFT
        }
        return Block(ls.first().page, left, top, right, bottom, ls, text, sizes[sizes.size / 2], lh.coerceAtLeast(sizes[0]), align, styles, runs)
    }

    /**
     * Computes how far each block may grow downwards without touching the next block below it
     * (or the page bottom margin), so longer translations reflow instead of shrinking.
     */
    fun assignRoom(blocks: List<Block>, pageBottom: (Int) -> Float, pageRight: (Int) -> Float = { Float.MAX_VALUE }) {
        val byPage = blocks.groupBy { it.page }
        for (b in blocks) {
            val same = byPage[b.page]!!
            var limit = pageBottom(b.page)
            for (o in same) {
                if (o === b || o.top < b.bottom - 0.5f) continue
                val overlap = min(o.right, b.right) - max(o.left, b.left)
                if (overlap > 0) limit = min(limit, o.top - b.lineHeight * 0.25f)
            }
            b.maxBottom = max(b.bottom, limit)
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
            b.maxRight = max(b.right, right)
            b.minLeft = min(b.left, left)
        }
    }

    /**
     * Wraps styled [regions] into the block's width. Uses the original height first, then the free
     * room below the block, and only then shrinks the font (down to [minScale]).
     */
    fun fit(block: Block, regions: List<Region>, m: TextMeasurer, minScale: Float = 0.6f): Placement {
        val words = toWords(block, regions)
        val single = block.lines.size == 1
        val (left, right) = when {
            !single -> block.left to block.right
            block.align == Align.CENTER -> {
                val c = (block.left + block.right) / 2; val half = min(c - block.minLeft, block.maxRight - c)
                (c - half) to (c + half)
            }
            block.align == Align.RIGHT -> block.minLeft to block.right
            else -> block.left to block.maxRight
        }
        val maxW = max(right - left, 4f)
        val ratio = block.lineHeight / block.fontSize.coerceAtLeast(1f)
        val room = block.maxBottom - block.top
        var size = block.fontSize
        var lines = wrap(words, maxW, size, m)
        while (size > block.fontSize * minScale) {
            val h = (lines.size - 1) * size * ratio + size
            if (h <= max(block.height, room) + 0.5f) break
            size *= 0.95f
            lines = wrap(words, maxW, size, m)
        }
        val lh = size * ratio
        val ascent = size * 0.8f
        val firstBaselineOffset = (block.lines.first().height * 0.78f).coerceAtLeast(ascent * 0.9f).coerceAtMost(ascent * 1.2f)
        val placed = lines.mapIndexed { i, ws ->
            val w = ws.sumOf { it.width.toDouble() }.toFloat()
            val startX = when (block.align) {
                Align.CENTER -> left + (maxW - w) / 2
                Align.RIGHT -> right - w
                else -> left
            }
            var x = startX
            val segs = ArrayList<Segment>()
            for (wd in ws) { segs.add(Segment(wd.text, wd.style, x, wd.width)); x += wd.width }
            PlacedLine(merge(segs), block.top + firstBaselineOffset + i * lh)
        }
        return Placement(block, size, placed)
    }

    /** Plain (single-style) fit, used by photo overlays. */
    fun fit(block: Block, text: String, m: TextMeasurer, minScale: Float = 0.6f): Placement = fit(block, listOf(Region(styleIndex(block), text)), m, minScale)

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

    private fun wrap(words: List<Word>, maxW: Float, size: Float, m: TextMeasurer): List<List<Word>> {
        val lines = ArrayList<List<Word>>()
        var cur = ArrayList<Word>(); var w = 0f
        for (word in words) {
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

    private fun merge(segs: List<Segment>): List<Segment> {
        val out = ArrayList<Segment>()
        for (s in segs) {
            val l = out.lastOrNull()
            if (l != null && l.style == s.style) out[out.size - 1] = Segment(l.text + s.text, l.style, l.x, l.width + s.width) else out.add(s)
        }
        return out
    }
}
