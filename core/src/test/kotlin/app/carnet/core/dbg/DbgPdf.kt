package app.carnet.core.dbg
import app.carnet.core.bench.AssetFonts
import app.carnet.core.layout.Layout
import app.carnet.pdf.PdfTranslator
import org.apache.pdfbox.pdmodel.PDDocument
import java.io.File
/** Dumps extracted lines and blocks of one page: DbgPdf <file> <page> */
fun main(a: Array<String>) {
    PDDocument.load(File(a[0])).use { doc ->
        val p = a.getOrNull(1)?.toInt() ?: 0
        val tr0 = PdfTranslator(AssetFonts())
        val lines = tr0.extract(doc).filter { it.page == p }
        val rs = tr0.rulesOf(p); println("rules: ${rs.horizontal.size} h, ${rs.vertical.size} v " + rs.vertical.take(8).map { "x=%.0f %.0f-%.0f".format(it.pos, it.from, it.to) })
        for (l in lines) println("L %.1f-%.1f y=%.1f-%.1f sz=%.1f '%s'".format(l.left, l.right, l.top, l.bottom, l.fontSize, l.text.take(70)))
        if (a.size > 2) {
            val all = PdfTranslator(AssetFonts()).extract(doc)
            val tr = app.carnet.core.mt.TextTranslator(app.carnet.core.mt.MarianSentenceTranslator(app.carnet.core.mt.MarianModel(app.carnet.core.mt.DirectorySource(File(a[2])))))
            val bs = Layout.blocks(all)
            val jobs = bs.map { app.carnet.core.doc.RunMapper.Job(it.runs) }
            app.carnet.core.doc.RunMapper.run(jobs, tr)
            bs.forEachIndexed { i, b -> if (b.page == p) println("${b.text}\n  → ${jobs[i].result?.joinToString("|") { it.text }}") }
            System.exit(0)
        }
        for (b in Layout.blocks(lines, rules = tr0::rulesOf)) println("B %.0f-%.0f y=%.0f-%.0f sz=%.1f lh=%.1f %s n=%d '%s'".format(b.left, b.right, b.top, b.bottom, b.fontSize, b.lineHeight, b.align, b.lines.size, b.text.take(80)))
    }
}
