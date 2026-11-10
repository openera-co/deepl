package app.carnet.core.dbg

import app.carnet.core.bench.AssetFonts
import app.carnet.pdf.PdfTranslator
import org.apache.pdfbox.pdmodel.PDDocument
import java.io.File

fun main(args: Array<String>) {
    PDDocument.load(File(args[0])).use { doc ->
        val t = PdfTranslator(AssetFonts()); t.extract(doc)
        for ((p, g) in t.unmappedGlyphs) {
            println("page $p: ${g.size} unreadable glyphs")
            g.groupBy { (it.top / 4).toInt() }.toSortedMap().forEach { (_, row) -> println("  y=${"%.0f".format(row[0].top)} x=${"%.0f".format(row.minOf { it.left })}..${"%.0f".format(row.maxOf { it.right })} n=${row.size} ops=${row.map { it.op }.distinct().size}") }
        }
    }
}
