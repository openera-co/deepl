package app.carnet.core.layout

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Removes text ink from a region of an image while keeping what's behind it (paper texture,
 * ruled lines, coloured backgrounds). Ink = pixels far from the local background colour; each is
 * replaced by interpolating the nearest non-ink pixels on the same row, which preserves horizontal
 * structures like notebook lines.
 */
object Inpaint {
    /** [px] is ARGB, row-major, [w] wide. Region is [x0,x1) × [y0,y1). Returns the background colour used. */
    fun eraseInk(px: IntArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int, bg: Int, dilate: Int = 1): Int {
        val ax = max(0, x0); val ay = max(0, y0); val bx = min(w, x1); val by = min(h, y1)
        if (bx <= ax || by <= ay) return bg
        val rw = bx - ax; val rh = by - ay
        val bgL = lum(bg)
        // Threshold relative to how much the ink contrasts with the paper here.
        var maxD = 0
        for (y in ay until by) for (x in ax until bx) maxD = max(maxD, dist(px[y * w + x], bg))
        val t = max(32, (maxD * 0.2f).roundToInt())
        val mask = BooleanArray(rw * rh)
        for (y in 0 until rh) for (x in 0 until rw) {
            val p = px[(ay + y) * w + ax + x]
            mask[y * rw + x] = dist(p, bg) > t && abs(lum(p) - bgL) > 18
        }
        // Grow the mask to swallow anti-aliased glyph edges.
        val grown = if (dilate <= 0) mask else BooleanArray(mask.size).also { g ->
            for (y in 0 until rh) for (x in 0 until rw) if (mask[y * rw + x]) {
                for (dy in -dilate..dilate) for (dx in -dilate..dilate) {
                    val yy = y + dy; val xx = x + dx
                    if (yy in 0 until rh && xx in 0 until rw) g[yy * rw + xx] = true
                }
            }
        }
        for (y in 0 until rh) {
            val row = (ay + y) * w + ax
            var x = 0
            while (x < rw) {
                if (!grown[y * rw + x]) { x++; continue }
                var e = x
                while (e < rw && grown[y * rw + e]) e++
                // Colours just outside the masked run on this row (fall back to the paper colour).
                val left = if (x > 0) px[row + x - 1] else if (ax > 0) px[row - 1] else bg
                val right = if (e < rw) px[row + e] else if (bx < w) px[row + rw] else bg
                val l = if (dist(left, bg) > t) bg else left
                val r = if (dist(right, bg) > t) bg else right
                val n = e - x + 1
                for (k in x until e) px[row + k] = mix(l, r, (k - x + 1).toFloat() / n)
                x = e
            }
        }
        return bg
    }

    /** Median colour of the ring just outside a box: the paper behind the text. */
    fun background(px: IntArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        val l = max(0, x0); val t = max(0, y0); val r = min(w - 1, x1); val b = min(h - 1, y1)
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        val step = max(1, ((r - l) + (b - t)) / 150)
        fun add(p: Int) { rs.add((p shr 16) and 255); gs.add((p shr 8) and 255); bs.add(p and 255) }
        for (x in l..r step step) { add(px[t * w + x]); add(px[b * w + x]) }
        for (y in t..b step step) { add(px[y * w + l]); add(px[y * w + r]) }
        if (rs.isEmpty()) return 0xFFFFFFFF.toInt()
        rs.sort(); gs.sort(); bs.sort()
        return (0xFF shl 24) or (rs[rs.size / 2] shl 16) or (gs[gs.size / 2] shl 8) or bs[bs.size / 2]
    }

    /** Average colour of strong ink pixels in a box (for redrawing text in the same colour). */
    fun ink(px: IntArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int, bg: Int): Int {
        val bgL = lum(bg)
        val inks = ArrayList<Int>()
        val step = max(1, ((x1 - x0) * (y1 - y0)) / 40000)
        var i = 0
        for (y in max(0, y0) until min(h, y1)) for (x in max(0, x0) until min(w, x1)) {
            if (i++ % step != 0) continue
            val p = px[y * w + x]
            if (abs(lum(p) - bgL) > 60) inks.add(p)
        }
        if (inks.size < 8) return if (bgL > 128) 0xFF111111.toInt() else 0xFFF0F0F0.toInt()
        // The core of the strokes (most contrasted third) is the real ink; edges are blended with paper.
        inks.sortBy { -abs(lum(it) - bgL) }
        val core = inks.subList(0, max(1, inks.size / 3))
        var rs = 0L; var gs = 0L; var bs = 0L
        for (p in core) { rs += (p shr 16) and 255; gs += (p shr 8) and 255; bs += p and 255 }
        val n = core.size
        return (0xFF shl 24) or ((rs / n).toInt() shl 16) or ((gs / n).toInt() shl 8) or (bs / n).toInt()
    }

    fun lum(c: Int) = (299 * ((c shr 16) and 255) + 587 * ((c shr 8) and 255) + 114 * (c and 255)) / 1000
    private fun dist(a: Int, b: Int) = abs(((a shr 16) and 255) - ((b shr 16) and 255)) + abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255))
    private fun mix(a: Int, b: Int, f: Float): Int {
        fun ch(s: Int) = (((a shr s) and 255) * (1 - f) + ((b shr s) and 255) * f).roundToInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
