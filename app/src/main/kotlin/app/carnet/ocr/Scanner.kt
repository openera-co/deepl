package app.carnet.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import app.carnet.core.layout.Block
import app.carnet.core.layout.Inpaint
import app.carnet.core.layout.Layout
import app.carnet.core.layout.Style
import app.carnet.core.layout.TextLine
import app.carnet.core.layout.TextMeasurer
import app.carnet.core.text.Segmenter
import app.carnet.engine.Engine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Result of scanning one page. */
data class ScanPage(
    val original: Bitmap,
    val translated: Bitmap,
    val frenchText: String,
    val englishText: String,
    val blocks: Int,
)

/**
 * Photo → French text (ML Kit, fully on-device) → English → English drawn over the photo
 * in the same place, colour and alignment, on a background matched to the paper.
 */
class Scanner(private val context: Context, private val engine: Engine) {
    companion object { const val HTR_ID = "htr-fr" }
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val sans by lazy { Typeface.createFromAsset(context.assets, "fonts/LiberationSans-Regular.ttf") }
    private val sansBold by lazy { Typeface.createFromAsset(context.assets, "fonts/LiberationSans-Bold.ttf") }

    /** Decodes an image honouring EXIF rotation, downscaled so the long side ≤ [maxSide]. */
    fun load(uri: Uri, maxSide: Int = 2600): Bitmap {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
        val bmp = cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: error("Can't read this image")
        val rotation = runCatching { cr.openInputStream(uri)!!.use { ExifInterface(it).rotationDegrees } }.getOrDefault(0)
        return normalize(bmp, rotation, maxSide)
    }

    fun load(file: File, maxSide: Int = 2600): Bitmap = load(Uri.fromFile(file), maxSide)

    fun normalize(bmp: Bitmap, rotation: Int, maxSide: Int = 2600): Bitmap {
        val scale = min(1f, maxSide.toFloat() / max(bmp.width, bmp.height))
        if (rotation == 0 && scale == 1f) return bmp.copy(Bitmap.Config.ARGB_8888, false)
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    fun rotate(bmp: Bitmap, degrees: Int): Bitmap = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    private suspend fun recognize(bmp: Bitmap): com.google.mlkit.vision.text.Text = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    /** Lines in reading order, grouped per ML Kit block. */
    suspend fun lines(bmp: Bitmap): Pair<List<TextLine>, List<Float>> {
        val text = recognize(bmp)
        val lines = ArrayList<TextLine>()
        val angles = ArrayList<Float>()
        val blocks = text.textBlocks.sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
        // French pages get the recognizer's systematic French mistakes corrected (accents on capitals,
        // "I'" for "l'", "ler" for "1er", "oe" for "œ"…; see OcrFix and the OCR benchmark).
        val french = app.carnet.core.text.LanguageGuess.guess(text.text) == "fr"
        for (b in blocks) for (l in b.lines) {
            val r = l.boundingBox ?: continue
            val h = r.height().toFloat()
            val t = app.carnet.core.text.OcrFix.fix(l.text, french)
            lines.add(TextLine(0, r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), t, h * 0.98f,
                runs = listOf(app.carnet.core.layout.StyledRun(t, Style()))))
            angles.add(l.angle)
        }
        return lines to angles
    }

    /** The handwriting recognizer (downloaded model "htr-fr"), loaded on first use. */
    private var htr: app.carnet.core.htr.HtrModel? = null
    private val htrLock = kotlinx.coroutines.sync.Mutex()
    val handwritingReady: Boolean get() = app.carnet.CarnetApp.instance.models.isInstalled(HTR_ID)

    /**
     * Handwriting: the page's text lines are found by the regular recognizer, then each line is
     * re-read by the handwriting model from its crop of the photo.
     */
    private suspend fun readHandwriting(bmp: Bitmap, lines: List<TextLine>): List<TextLine> = htrLock.withLock {
        val m = htr ?: app.carnet.core.htr.HtrModel(app.carnet.core.mt.DirectorySource(app.carnet.CarnetApp.instance.models.dir(HTR_ID))).also { htr = it }
        val crops = lines.map { l ->
            val padY = (l.height * 0.2f).toInt() + 2; val padX = (l.height * 0.3f).toInt() + 2
            val x0 = (l.left.toInt() - padX).coerceIn(0, bmp.width - 1); val y0 = (l.top.toInt() - padY).coerceIn(0, bmp.height - 1)
            val x1 = (l.right.toInt() + padX).coerceIn(x0 + 1, bmp.width); val y1 = (l.bottom.toInt() + padY).coerceIn(y0 + 1, bmp.height)
            val w = x1 - x0; val h = y1 - y0
            val px = IntArray(w * h); bmp.getPixels(px, 0, w, x0, y0, w, h)
            Triple(px, w, h)
        }
        val texts = m.recognize(crops)
        val french = app.carnet.core.text.LanguageGuess.guess(texts.joinToString(" ")) != "en"
        lines.mapIndexed { i, l ->
            val t = app.carnet.core.text.OcrFix.fix(texts[i].ifBlank { l.text }, french)
            l.copy(text = t, runs = listOf(app.carnet.core.layout.StyledRun(t, Style())))
        }
    }

    /** Full pipeline for one page. [onStage] reports "reading" / "translating" / "drawing". */
    suspend fun scan(bmp: Bitmap, handwriting: Boolean = false, onStage: (String) -> Unit = {}): ScanPage = withContext(Dispatchers.Default) {
        val timing = app.carnet.diag.Diag.timing("scan.page").tag("handwriting", handwriting.toString())
        onStage("reading")
        val (found, angles) = timing.span("ocr") { kotlinx.coroutines.runBlocking { lines(bmp) } }
        val lines = if (handwriting && handwritingReady && found.isNotEmpty()) readHandwriting(bmp, found) else found
        val blocks = Layout.blocks(lines, sizeTolerance = 0.3f)
        Layout.assignRoom(blocks, { bmp.height.toFloat() - 8f }, { bmp.width.toFloat() })
        val french = blocks.joinToString("\n\n") { it.text }
        onStage("translating")
        val ling = blocks.filter { Segmenter.isLinguistic(it.text) }
        val tTr = System.nanoTime()
        val english = if (ling.isEmpty()) emptyList() else engine.translate(ling.map { it.text })
        timing.measure("translate_ms", (System.nanoTime() - tTr) / 1_000_000).measure("blocks", ling.size)
        val trMap = HashMap<Block, String>(); ling.forEachIndexed { i, b -> trMap[b] = english[i] }
        onStage("drawing")
        val out = timing.span("draw") { render(bmp, blocks, trMap, if (angles.isEmpty()) 0f else angles.sorted()[angles.size / 2]) }
        timing.measure("lines", lines.size).measure("pixels", bmp.width.toLong() * bmp.height).ok()
        ScanPage(bmp, out, french, blocks.joinToString("\n\n") { trMap[it] ?: it.text }, blocks.size)
    }

    /** Redraws [blocks] with their translations onto a copy of [src]. */
    fun render(src: Bitmap, blocks: List<Block>, translations: Map<Block, String>, angle: Float): Bitmap {
        val t0 = System.nanoTime()
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val orig = px.copyOf()
        // 1) Erase the French ink only (paper, ruled lines and backgrounds stay).
        val colours = HashMap<Block, Pair<Int, Int>>()
        for (b in blocks) {
            if (translations[b] == null) continue
            val bg = Inpaint.background(orig, w, h, b.left.toInt() - 4, b.top.toInt() - 4, b.right.toInt() + 4, b.bottom.toInt() + 4)
            val ink = Inpaint.ink(orig, w, h, b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt(), bg)
            colours[b] = bg to ink
            for (l in b.lines) {
                val pad = (l.height * 0.15f).toInt() + 1
                val lbg = Inpaint.background(orig, w, h, l.left.toInt() - pad, l.top.toInt() - pad, l.right.toInt() + pad, l.bottom.toInt() + pad)
                Inpaint.eraseInk(px, w, h, l.left.toInt() - pad, l.top.toInt() - pad, l.right.toInt() + pad, l.bottom.toInt() + pad, lbg, dilate = max(2, (l.height / 12).toInt()))
            }
        }
        val tErase = System.nanoTime()
        val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
        // 2) Draw the English in the same place and colour.
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        for (b in blocks) {
            val tr = translations[b] ?: continue
            val (bg, fg) = colours[b] ?: continue
            paint.typeface = if (isBold(src, b, bg)) sansBold else sans
            paint.color = fg
            val measurer = TextMeasurer { text, size, _ -> paint.textSize = size; paint.measureText(text) }
            val placement = Layout.fit(b, tr, measurer)
            paint.textSize = placement.fontSize
            c.save()
            if (abs(angle) in 0.5f..15f) c.rotate(angle, b.left, b.top)
            for (pl in placement.lines) for (seg in pl.segments) c.drawText(seg.text, seg.x, pl.baseline, paint)
            c.restore()
        }
        android.util.Log.i("CarnetScan", "render ${w}x$h blocks=${blocks.size}: erase ${(tErase - t0) / 1_000_000} ms, draw ${(System.nanoTime() - tErase) / 1_000_000} ms")
        return out
    }

    private fun backgroundOf(src: Bitmap, b: Block): Int = backgroundOf(src, b.left, b.top, b.right, b.bottom)

    /** Median colour of a thin ring just outside the box (the paper behind the text). */
    private fun backgroundOf(src: Bitmap, l: Float, t: Float, r: Float, btm: Float): Int {
        val pad = max(3f, (btm - t) * 0.25f)
        val rect = Rect((l - pad).toInt().coerceAtLeast(0), (t - pad).toInt().coerceAtLeast(0), (r + pad).toInt().coerceAtMost(src.width - 1), (btm + pad).toInt().coerceAtMost(src.height - 1))
        val samples = ArrayList<Int>()
        val step = max(1, (rect.width() + rect.height()) / 120)
        for (x in rect.left..rect.right step step) { samples.add(src.getPixel(x, rect.top)); samples.add(src.getPixel(x, rect.bottom)) }
        for (y in rect.top..rect.bottom step step) { samples.add(src.getPixel(rect.left, y)); samples.add(src.getPixel(rect.right, y)) }
        return medianColor(samples)
    }

    /** Ink colour: the average of pixels clearly different from the background. */
    private fun inkOf(src: Bitmap, b: Block, bg: Int): Int {
        val bgL = lum(bg)
        var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
        for (l in b.lines) {
            val y0 = l.top.toInt().coerceIn(0, src.height - 1); val y1 = l.bottom.toInt().coerceIn(0, src.height - 1)
            val x0 = l.left.toInt().coerceIn(0, src.width - 1); val x1 = l.right.toInt().coerceIn(0, src.width - 1)
            val step = max(1, (x1 - x0) / 160)
            for (y in y0..y1 step max(1, (y1 - y0) / 12)) for (x in x0..x1 step step) {
                val p = src.getPixel(x, y)
                if (abs(lum(p) - bgL) > 70) { rs += Color.red(p); gs += Color.green(p); bs += Color.blue(p); n++ }
            }
        }
        if (n < 10) return if (bgL > 128) Color.BLACK else Color.WHITE
        // Darken/lighten slightly: anti-aliased edges make the average lighter than the real ink.
        val k = if (bgL > 128) 0.8f else 1.15f
        return Color.rgb((rs / n * k).toInt().coerceIn(0, 255), (gs / n * k).toInt().coerceIn(0, 255), (bs / n * k).toInt().coerceIn(0, 255))
    }

    /** Bold if ink covers a large share of the text area for its height. */
    private fun isBold(src: Bitmap, b: Block, bg: Int): Boolean {
        val l = b.lines.first()
        val bgL = lum(bg)
        var ink = 0; var total = 0
        val y0 = l.top.toInt().coerceIn(0, src.height - 1); val y1 = l.bottom.toInt().coerceIn(0, src.height - 1)
        val x0 = l.left.toInt().coerceIn(0, src.width - 1); val x1 = l.right.toInt().coerceIn(0, src.width - 1)
        for (y in y0..y1 step max(1, (y1 - y0) / 16)) for (x in x0..x1 step max(1, (x1 - x0) / 200)) {
            total++; if (abs(lum(src.getPixel(x, y)) - bgL) > 70) ink++
        }
        return total > 0 && ink.toFloat() / total > 0.26f
    }

    private fun lum(c: Int) = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)).toInt()

    private fun medianColor(s: List<Int>): Int {
        if (s.isEmpty()) return Color.WHITE
        val r = s.map { Color.red(it) }.sorted(); val g = s.map { Color.green(it) }.sorted(); val b = s.map { Color.blue(it) }.sorted()
        return Color.rgb(r[r.size / 2], g[g.size / 2], b[b.size / 2])
    }
}
