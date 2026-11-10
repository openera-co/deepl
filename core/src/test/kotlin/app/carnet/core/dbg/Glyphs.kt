package app.carnet.core.dbg

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.util.Matrix
import org.apache.pdfbox.util.Vector
import java.io.File

fun main(args: Array<String>) {
    PDDocument.load(File(args[0])).use { doc ->
        var n = 0
        val s = object : PDFTextStripper() {
            override fun showGlyph(trm: Matrix, font: PDFont, code: Int, unicode: String?, displacement: Vector) {
                if (font.name.contains("Courier") && n++ < 15) println("code=$code unicode=${unicode?.map { it.code }} y=${trm.translateY}")
                super.showGlyph(trm, font, code, unicode, displacement)
            }
        }
        s.startPage = 1; s.endPage = 1; s.getText(doc); println("courier glyphs: $n")
    }
}
