package app.carnet.ocr

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Image clean-up before text recognition. Variants are compared by the OCR benchmark
 * (app/src/androidTest OcrBench); "auto" is what the app uses.
 *  - invert: light writing on a dark background (blackboards, dark slides) → dark on light
 *  - upscale: small photos/text enlarged so letters are tall enough for the recognizer
 *  - contrast: uneven lighting and coloured paper evened out (local contrast stretch)
 */
object Preprocess {
    fun apply(src: Bitmap, variant: String): Bitmap = when (variant) {
        "default", "none" -> src
        "invert" -> if (isDark(src)) invert(src) else src
        "upscale" -> upscale(src)
        "contrast" -> contrast(src)
        "auto" -> auto(src)
        "all" -> contrast(upscale(if (isDark(src)) invert(src) else src))
        else -> src
    }

    fun auto(src: Bitmap): Bitmap {
        var b = src
        if (isDark(b)) b = invert(b)
        b = upscale(b)
        return b
    }

    private fun luma(c: Int) = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    /** Mostly dark image with light strokes (e.g. a blackboard). */
    fun isDark(b: Bitmap): Boolean {
        val w = b.width; val h = b.height
        var sum = 0L; var n = 0
        val step = max(1, min(w, h) / 64)
        var y = 0
        while (y < h) { var x = 0; while (x < w) { sum += luma(b.getPixel(x, y)); n++; x += step }; y += step }
        return n > 0 && sum / n < 100
    }

    fun invert(b: Bitmap): Bitmap {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) { val l = 255 - luma(px[i]); px[i] = (0xFF shl 24) or (l shl 16) or (l shl 8) or l }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Long side at least 2000 px (ML Kit wants letters ≥ ~16 px tall); never larger than 3000. */
    fun upscale(b: Bitmap): Bitmap {
        val long = max(b.width, b.height)
        if (long >= 2000) return b
        val s = min(3000f / long, 2000f / long)
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
    }

    /** Local contrast stretch on 64-px tiles (bilinear blend of tile min/max), greyscale. */
    fun contrast(b: Bitmap): Bitmap {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        val g = IntArray(w * h) { luma(px[it]) }
        val t = 64
        val tw = (w + t - 1) / t; val th = (h + t - 1) / t
        val lo = IntArray(tw * th) { 255 }; val hi = IntArray(tw * th)
        for (y in 0 until h) for (x in 0 until w) {
            val k = (y / t) * tw + x / t; val v = g[y * w + x]
            if (v < lo[k]) lo[k] = v; if (v > hi[k]) hi[k] = v
        }
        for (y in 0 until h) {
            val fy = (y.toFloat() / t - 0.5f).coerceIn(0f, (th - 1).toFloat()); val y0 = fy.toInt(); val y1 = min(y0 + 1, th - 1); val ay = fy - y0
            for (x in 0 until w) {
                val fx = (x.toFloat() / t - 0.5f).coerceIn(0f, (tw - 1).toFloat()); val x0 = fx.toInt(); val x1 = min(x0 + 1, tw - 1); val ax = fx - x0
                fun bl(a: IntArray) = (a[y0 * tw + x0] * (1 - ax) + a[y0 * tw + x1] * ax) * (1 - ay) + (a[y1 * tw + x0] * (1 - ax) + a[y1 * tw + x1] * ax) * ay
                val l = bl(lo); val hgh = bl(hi)
                val range = max(hgh - l, 40f)
                val v = (((g[y * w + x] - l) / range) * 255f).toInt().coerceIn(0, 255)
                px[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
