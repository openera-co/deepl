package app.carnet.convert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import app.carnet.core.convert.DocxWriter
import app.carnet.core.convert.OdtWriter
import app.carnet.core.convert.Readers
import app.carnet.pdf.AndroidFonts
import app.carnet.pdf.PdfToModel
import app.carnet.pdf.PdfWriter
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** All file conversions available in Tools. Everything runs offline. */
object Converters {

    /** Any supported document (Word, ODT, PowerPoint, Excel, text, Markdown, CSV) → PDF. */
    fun toPdf(context: Context, input: File, output: File) {
        val ext = input.extension.lowercase()
        when {
            ext == "pdf" -> input.copyTo(output, true)
            ext in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic") -> imagesToPdf(listOf(BitmapFactory.decodeFile(input.path)), output)
            else -> {
                val model = input.inputStream().use { Readers.read(input.name, it) }
                output.outputStream().use { PdfWriter(AndroidFonts(context)).write(model, it) }
            }
        }
    }

    fun toDocx(context: Context, input: File, output: File) {
        val model = if (input.extension.equals("pdf", true)) PdfToModel.read(input, AndroidFonts(context)) else input.inputStream().use { Readers.read(input.name, it) }
        output.outputStream().use { DocxWriter.write(model, it) }
    }

    fun toOdt(context: Context, input: File, output: File) {
        val model = if (input.extension.equals("pdf", true)) PdfToModel.read(input, AndroidFonts(context)) else input.inputStream().use { Readers.read(input.name, it) }
        output.outputStream().use { OdtWriter.write(model, it) }
    }

    /** One page per image, A4, keeping each image's orientation. */
    fun imagesToPdf(images: List<Bitmap>, output: File, marginPt: Int = 24) {
        val doc = PdfDocument()
        try {
            images.forEachIndexed { i, bmp ->
                val landscape = bmp.width > bmp.height
                val (pw, ph) = if (landscape) 842 to 595 else 595 to 842
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                val c: Canvas = page.canvas
                c.drawColor(Color.WHITE)
                val scale = min((pw - 2f * marginPt) / bmp.width, (ph - 2f * marginPt) / bmp.height)
                val w = (bmp.width * scale).toInt(); val h = (bmp.height * scale).toInt()
                val left = (pw - w) / 2; val top = (ph - h) / 2
                c.drawBitmap(bmp, null, Rect(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
                doc.finishPage(page)
            }
            output.outputStream().use { doc.writeTo(it) }
        } finally { doc.close() }
    }

    /** Renders each page as a PNG at ~150 dpi. */
    fun pdfToImages(input: File, outDir: File, scale: Float = 2.08f): List<File> {
        outDir.mkdirs()
        val out = ArrayList<File>()
        PdfRenderer(ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            for (i in 0 until r.pageCount) r.openPage(i).use { p ->
                val bmp = Bitmap.createBitmap((p.width * scale).toInt(), (p.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val f = File(outDir, "page-${i + 1}.png")
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                out.add(f)
            }
        }
        return out
    }

    /** Renders one page for previews. */
    /** A page as an image plus its size in points (for mapping image positions back to the page). */
    fun renderPageWithSize(input: File, index: Int, widthPx: Int): Triple<Bitmap, Float, Float>? = runCatching {
        PdfRenderer(ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            if (index !in 0 until r.pageCount) return null
            r.openPage(index).use { p ->
                val scale = widthPx.toFloat() / p.width
                val bmp = Bitmap.createBitmap(widthPx, (p.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                Triple(bmp, p.width.toFloat(), p.height.toFloat())
            }
        }
    }.getOrNull()

    fun renderPage(input: File, index: Int, widthPx: Int): Bitmap? = runCatching {
        PdfRenderer(ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            if (r.pageCount == 0) return null
            r.openPage(index.coerceIn(0, r.pageCount - 1)).use { p ->
                val scale = widthPx.toFloat() / p.width
                val bmp = Bitmap.createBitmap(widthPx, (p.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }.getOrNull()

    fun pageCount(input: File): Int = runCatching {
        PdfRenderer(ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }
    }.getOrDefault(0)

    /** Recompresses embedded images (downscaled to [maxSide] px, JPEG [quality]). */
    fun compressPdf(input: File, output: File, quality: Float = 0.6f, maxSide: Int = 1600): Pair<Long, Long> {
        PDDocument.load(input).use { doc ->
            for (page in doc.pages) {
                val res = page.resources ?: continue
                for (name in res.xObjectNames.toList()) {
                    val x = res.getXObject(name) as? PDImageXObject ?: continue
                    val bmp: Bitmap = runCatching { x.image }.getOrNull() ?: continue
                    val s = min(1f, maxSide.toFloat() / max(bmp.width, bmp.height))
                    val scaled = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
                    // Images with transparency keep their mask by staying lossless.
                    if (x.cosObject.containsKey(COSName.SMASK) || x.cosObject.containsKey(COSName.MASK)) continue
                    val jpeg = JPEGFactory.createFromImage(doc, scaled, quality)
                    res.put(name, jpeg)
                }
            }
            output.outputStream().use { doc.save(it) }
        }
        return input.length() to output.length()
    }
}
