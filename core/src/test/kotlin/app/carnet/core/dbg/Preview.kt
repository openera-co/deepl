package app.carnet.core.dbg

import app.carnet.core.bench.AssetFonts
import app.carnet.pdf.PdfWriter
import app.carnet.core.convert.Readers
import java.io.File

/** What the app's result screen shows for a non-PDF file: Readers → PdfWriter. args: outDir files… */
fun main(args: Array<String>) {
    val out = File(args[0]).also { it.mkdirs() }
    for (f in args.drop(1).map { File(it) }) {
        val pdf = File(out, f.nameWithoutExtension + ".preview.pdf")
        runCatching { pdf.outputStream().use { PdfWriter(AssetFonts()).write(f.inputStream().use { Readers.read(f.name, it) }, it) } }
            .onFailure { println("${f.name}: $it") }.onSuccess { println("${f.name} → ${pdf.name}") }
    }
}
