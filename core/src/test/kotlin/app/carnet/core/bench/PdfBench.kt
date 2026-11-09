package app.carnet.core.bench

import app.carnet.core.mt.TextTranslator
import app.carnet.pdf.FontProvider
import app.carnet.pdf.PdfTranslator
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.InputStream

/** Desktop font provider reading the same font files the app bundles. */
class AssetFonts(private val dir: File = File("../app/src/main/assets/fonts").takeIf { it.exists() } ?: File("app/src/main/assets/fonts")) : FontProvider {
    override fun open(family: String, bold: Boolean, italic: Boolean): InputStream {
        val style = when { bold && italic -> "BoldItalic"; bold -> "Bold"; italic -> "Italic"; else -> "Regular" }
        val name = when (family) {
            "calibri" -> "Carlito-$style"
            "serif" -> "LiberationSerif-$style"
            "mono" -> "LiberationMono-" + (if (bold) "Bold" else "Regular")
            else -> "LiberationSans-$style"
        }
        return File(dir, "$name.ttf").inputStream()
    }
    override fun fallback(): InputStream = File(dir, "DejaVuSans.ttf").inputStream()
}

object PdfBench {
    fun translate(src: File, out: File, tr: TextTranslator): Long {
        val t0 = System.nanoTime()
        out.outputStream().use { PdfTranslator(AssetFonts()).translate(src, it, tr) }
        return (System.nanoTime() - t0) / 1_000_000
    }

    private fun images(d: PDDocument) = d.pages.sumOf { p -> p.resources.xObjectNames.count { p.resources.getXObject(it) is PDImageXObject } }

    fun check(src: File, out: File, fake: Boolean): List<Check> {
        val checks = ArrayList<Check>()
        PDDocument.load(src).use { a ->
            PDDocument.load(out).use { b ->
                checks += Check("pdf pages kept", a.numberOfPages == b.numberOfPages, "${a.numberOfPages}→${b.numberOfPages}")
                checks += Check("pdf images kept", images(a) == images(b), "${images(a)}→${images(b)}")
                val ta = PDFTextStripper().getText(a); val tb = PDFTextStripper().getText(b)
                val words = Regex("[\\p{L}]{5,}").findAll(ta).map { it.value }.toList()
                val left = words.windowed(3).map { it.joinToString(" ") }.filter { tb.contains(it) }.distinct()
                checks += Check("pdf fully translated", left.isEmpty(), left.take(3).toString())
                checks += Check("pdf has text", tb.isNotBlank(), "")
                // Every glyph must be drawn inside the page.
                val stripper = object : PDFTextStripper() {
                    var outside = 0
                    override fun processTextPosition(text: org.apache.pdfbox.text.TextPosition) {
                        val box = currentPage.cropBox
                        if (text.xDirAdj < -1 || text.xDirAdj + text.widthDirAdj > box.width + 1 || text.yDirAdj > box.height + 1 || text.yDirAdj < 0) outside++
                        super.processTextPosition(text)
                    }
                }
                stripper.getText(b)
                checks += Check("pdf text inside page", stripper.outside == 0, "${stripper.outside} glyphs outside")
            }
        }
        return checks
    }
}
