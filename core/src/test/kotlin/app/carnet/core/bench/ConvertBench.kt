package app.carnet.core.bench

import app.carnet.core.convert.DocxWriter
import app.carnet.core.convert.OdtWriter
import app.carnet.core.convert.Readers
import app.carnet.pdf.PdfToModel
import app.carnet.pdf.PdfWriter
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File

/** Conversion checks: X → PDF keeps the text; PDF → Word keeps the text; Word ↔ ODT round-trips. */
object ConvertBench {
    private fun words(s: String) = Regex("[\\p{L}]{4,}").findAll(s.lowercase()).map { it.value }.toList()

    private fun coverage(src: String, out: String): Double {
        val a = words(src); if (a.isEmpty()) return 1.0
        val b = words(out).groupingBy { it }.eachCount().toMutableMap()
        var hit = 0
        for (w in a) { val c = b[w] ?: 0; if (c > 0) { hit++; b[w] = c - 1 } }
        return hit.toDouble() / a.size
    }

    fun run(src: File, outDir: File): Pair<Long, List<Check>> {
        val checks = ArrayList<Check>()
        val t0 = System.nanoTime()
        val ext = src.extension.lowercase()
        if (ext == "pdf") {
            val model = PdfToModel.read(src, AssetFonts())
            val docx = File(outDir, src.nameWithoutExtension + ".docx")
            docx.outputStream().use { DocxWriter.write(model, it) }
            val back = Readers.read("x.docx", docx.inputStream())
            val pdfText = PDDocument.load(src).use { PDFTextStripper().getText(it) }
            val cov = coverage(pdfText, back.plainText())
            checks += Check("pdf→docx keeps text", cov > 0.97, "%.3f".format(cov))
        } else {
            val model = Readers.read(src.name, src.inputStream())
            val pdf = File(outDir, src.nameWithoutExtension + ".$ext.pdf")
            pdf.outputStream().use { PdfWriter(AssetFonts()).write(model, it) }
            val text = PDDocument.load(pdf).use { d ->
                checks += Check("pdf has pages", d.numberOfPages > 0, "${d.numberOfPages}")
                PDFTextStripper().getText(d)
            }
            val cov = coverage(model.plainText(), text)
            checks += Check("→pdf keeps text", cov > 0.97, "%.3f".format(cov))
            if (ext == "docx" || ext == "odt") {
                val other = File(outDir, src.nameWithoutExtension + if (ext == "docx") ".odt" else ".docx")
                other.outputStream().use { if (ext == "docx") OdtWriter.write(model, it) else DocxWriter.write(model, it) }
                val back = Readers.read(other.name, other.inputStream())
                val c2 = coverage(model.plainText(), back.plainText())
                checks += Check("docx↔odt keeps text", c2 > 0.98, "%.3f".format(c2))
            }
        }
        return (System.nanoTime() - t0) / 1_000_000 to checks
    }
}
