package app.carnet.jobs

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.zip.ZipFile

/** A few thousand characters of a document's text, to tell French from English under "Auto". */
object DocSample {
    private val tag = Regex("<[^>]+>")

    fun of(file: File, limit: Int = 6000): String = runCatching {
        when (file.extension.lowercase()) {
            "pdf" -> PDDocument.load(file).use { d -> PDFTextStripper().apply { startPage = 1; endPage = minOf(3, d.numberOfPages) }.getText(d) }
            "docx", "pptx", "xlsx", "odt", "ods", "odp", "docm", "dotx", "pptm", "xlsm" -> ZipFile(file).use { z ->
                val sb = StringBuilder()
                val parts = z.entries().toList().filter { e -> e.name.endsWith(".xml") && (e.name.startsWith("word/document") || e.name.startsWith("ppt/slides/slide") ||
                    e.name == "xl/sharedStrings.xml" || e.name == "content.xml") }
                for (e in parts) {
                    if (sb.length > limit) break
                    val xml = z.getInputStream(e).use { String(it.readNBytes(400_000), Charsets.UTF_8) }
                    sb.append(xml.replace(tag, " ")).append(' ')
                }
                sb.toString()
            }
            else -> file.inputStream().use { String(it.readNBytes(limit * 2), Charsets.UTF_8) }
        }.replace(Regex("\\s+"), " ").take(limit)
    }.getOrDefault("")

    /** Proofing language for the translated file. */
    fun targetTag(target: String, spelling: String) = if (target == "fr") "fr-FR" else if (spelling == "US") "en-US" else "en-GB"
}
