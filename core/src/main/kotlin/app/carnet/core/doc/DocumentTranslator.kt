package app.carnet.core.doc

import app.carnet.core.mt.TextTranslator
import java.io.InputStream
import java.io.OutputStream

enum class DocFormat(val extensions: Set<String>) {
    DOCX(setOf("docx", "docm", "dotx")),
    PPTX(setOf("pptx", "pptm", "potx", "ppsx")),
    XLSX(setOf("xlsx", "xlsm", "xltx")),
    ODF(setOf("odt", "ods", "odp", "ott", "odg")),
    /** Word 97–2003: translated into .docx. */
    DOC(setOf("doc", "dot")),
    /** Excel 97–2003: translated in place. */
    XLS(setOf("xls", "xlt")),
    RTF(setOf("rtf")),
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
    /** Word/ODT: keep each original paragraph (in grey) above its translation, for revision. */
    val bilingual: Boolean = false,
)

data class DocStats(var paragraphs: Int = 0, var translated: Int = 0, var characters: Int = 0, var parts: Int = 0,
    /** Extension the translated file must have when it differs from the input's (.doc → .docx). */
    var outputExtension: String? = null)

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
        DocFormat.TXT, DocFormat.DOC, DocFormat.XLS, DocFormat.RTF -> null
    }

    /** Extension of the translated file before looking at its content (old .doc files become .docx). */
    fun outputExtension(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return if (DocFormat.fromName(name) == DocFormat.DOC) "docx" else ext
    }

    /** Translates any supported file. Plain-text formats are handled line by line. */
    fun translate(name: String, input: InputStream, output: OutputStream, translator: TextTranslator, options: TranslateOptions = TranslateOptions(), progress: Progress = object : Progress {}): DocStats {
        var format = DocFormat.fromName(name) ?: throw IllegalArgumentException("Unsupported file type: $name")
        // Files are often saved under the wrong extension: decide by content for the old formats.
        val buf = java.io.BufferedInputStream(input, 1 shl 16).apply { mark(16) }
        val head = ByteArray(8).let { b -> val n = buf.read(b); buf.reset(); String(b, 0, maxOf(0, n), Charsets.ISO_8859_1) }
        if (format == DocFormat.DOC || format == DocFormat.XLS || format == DocFormat.RTF) {
            val isZip = head.startsWith("PK")
            val isRtf = head.startsWith("{\\rtf")
            val isMarkup = head.trimStart('\uFEFF', ' ', '\r', '\n', '\t').startsWith("<")
            when {
                isZip -> {
                    val modern = if (format == DocFormat.XLS) DocFormat.XLSX else DocFormat.DOCX
                    return translatorFor(modern)!!.translate(buf, output, translator, options, progress).also { it.outputExtension = if (modern == DocFormat.XLSX) "xlsx" else "docx" }
                }
                isRtf -> return RtfTranslator.translate(buf, output, translator, progress).also { if (format != DocFormat.RTF) it.outputExtension = "rtf" }
                isMarkup -> return PlainText.translate("file.html", buf, output, translator, progress)
                format == DocFormat.RTF -> throw IllegalArgumentException("This file is empty or damaged")
            }
        }
        if (format == DocFormat.DOC) return LegacyOffice.translateDoc(buf, output, translator, options, progress).also { it.outputExtension = "docx" }
        if (format == DocFormat.XLS) return LegacyOffice.translateXls(buf, output, translator, progress)
        @Suppress("NAME_SHADOWING") val input = buf
        val zone = translatorFor(format)
        if (zone != null) return zone.translate(input, output, translator, options, progress)
        return PlainText.translate(name, input, output, translator, progress)
    }
}
