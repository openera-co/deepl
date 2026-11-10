package app.carnet.core.dbg

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.File

/** Prints each text line PDFBox extracts with its font, and whether glyphs lack Unicode. */
fun main(args: Array<String>) {
    PDDocument.load(File(args[0])).use { doc ->
        val s = object : PDFTextStripper() {
            override fun writeString(text: String, pos: MutableList<TextPosition>) {
                val f = pos.firstOrNull()?.font
                val bad = pos.count { it.unicode == null || it.unicode.any { c -> c == '\uFFFD' || c.code in 0xE000..0xF8FF || c.code < 32 } }
                println("${f?.name} toUnicode=${f?.cosObject?.containsKey(org.apache.pdfbox.cos.COSName.TO_UNICODE)} bad=$bad/${pos.size}: ${text.take(70)}")
            }
        }
        s.startPage = 1; s.endPage = 1; s.getText(doc)
    }
}
