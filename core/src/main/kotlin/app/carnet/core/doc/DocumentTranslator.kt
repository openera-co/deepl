package app.carnet.core.doc

import app.carnet.core.mt.TextTranslator
import java.io.InputStream
import java.io.OutputStream

enum class DocFormat(val extensions: Set<String>) {
    DOCX(setOf("docx", "docm", "dotx")),
    PPTX(setOf("pptx", "pptm", "potx", "ppsx")),
    XLSX(setOf("xlsx", "xlsm", "xltx")),
    ODF(setOf("odt", "ods", "odp", "ott", "odg")),
    TXT(setOf("txt", "md", "csv", "srt", "vtt", "html", "htm"));

    companion object {
        fun fromName(name: String): DocFormat? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions }
        }
    }
}

data class TranslateOptions(
    /** BCP-47 tag written into the document's proofing language. */
    val targetLanguage: String = "en-GB",
    val translateComments: Boolean = true,
    val translateNotes: Boolean = true,
)

data class DocStats(var paragraphs: Int = 0, var translated: Int = 0, var characters: Int = 0, var parts: Int = 0)

interface Progress {
    /** [done] of [total] translation units finished. */
    fun update(done: Int, total: Int) {}
    fun cancelled(): Boolean = false
}

/**
 * A job for the shared batching pipeline: the zone's runs plus a callback that writes the
 * translated regions back into the document.
 */
class ZoneJob(runs: List<RunText>, val apply: (List<Region>) -> Unit) {
    val job = RunMapper.Job(runs)
}

abstract class ZoneDocumentTranslator {
    /** Collects every translatable zone of the package; edits happen later via the callbacks. */
    protected abstract fun collect(pkg: ZipPackage, options: TranslateOptions, sink: MutableList<ZoneJob>, stats: DocStats): () -> Unit

    fun translate(input: InputStream, output: OutputStream, translator: TextTranslator, options: TranslateOptions = TranslateOptions(), progress: Progress = object : Progress {}): DocStats {
        val pkg = ZipPackage.read(input)
        val stats = DocStats()
        val zones = ArrayList<ZoneJob>()
        val commit = collect(pkg, options, zones, stats)
        RunMapper.run(zones.map { it.job }, translator, progress::cancelled) { d, t -> progress.update(d, t) }
        for (z in zones) {
            val r = z.job.result ?: continue
            z.apply(r)
            stats.translated++
            stats.characters += z.job.full.length
        }
        commit()
        pkg.write(output)
        return stats
    }
}

object Documents {
    fun translatorFor(format: DocFormat): ZoneDocumentTranslator? = when (format) {
        DocFormat.DOCX -> DocxTranslator()
        DocFormat.PPTX -> DrawingMLTranslator(DrawingMLTranslator.Kind.PPTX)
        DocFormat.XLSX -> XlsxTranslator()
        DocFormat.ODF -> OdfTranslator()
        DocFormat.TXT -> null
    }

    /** Translates any supported file. Plain-text formats are handled line by line. */
    fun translate(name: String, input: InputStream, output: OutputStream, translator: TextTranslator, options: TranslateOptions = TranslateOptions(), progress: Progress = object : Progress {}): DocStats {
        val format = DocFormat.fromName(name) ?: throw IllegalArgumentException("Unsupported file type: $name")
        val zone = translatorFor(format)
        if (zone != null) return zone.translate(input, output, translator, options, progress)
        return PlainText.translate(name, input, output, translator, progress)
    }
}
