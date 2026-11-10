package app.carnet.jobs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import app.carnet.app
import app.carnet.convert.Converters
import app.carnet.core.doc.DocFormat
import app.carnet.core.doc.Documents
import app.carnet.core.doc.Progress
import app.carnet.core.doc.TranslateOptions
import app.carnet.data.ItemType
import app.carnet.data.LibraryItem
import app.carnet.pdf.AndroidFonts
import app.carnet.pdf.PdfTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class JobState(
    val id: String,
    val title: String,
    val stage: String = "Preparing",
    val done: Int = 0,
    val total: Int = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val etaSeconds: Int? = null,
    val resultId: String? = null,
    val error: String? = null,
    val finished: Boolean = false,
) {
    val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
}

/** Runs long translations/conversions in the app scope and exposes their progress. */
class Jobs(private val context: Context) {
    private val states = ConcurrentHashMap<String, MutableStateFlow<JobState>>()
    private val cancels = ConcurrentHashMap<String, AtomicBoolean>()
    private val running = ConcurrentHashMap<String, Job>()

    fun state(id: String): StateFlow<JobState>? = states[id]

    fun cancel(id: String) { cancels[id]?.set(true); running[id]?.cancel() }

    /** Reads one PDF page visually (for text whose font has no letter mapping), in page points. */
    private fun ocrPage(pdf: File, page: Int): List<app.carnet.pdf.PdfTranslator.OcrLine> {
        val (bmp, wPt, _) = app.carnet.convert.Converters.renderPageWithSize(pdf, page, 2000) ?: return emptyList()
        val k = wPt / bmp.width
        val (lines, _) = kotlinx.coroutines.runBlocking { context.app.scanner.lines(bmp) }
        bmp.recycle()
        return lines.map { l -> app.carnet.pdf.PdfTranslator.OcrLine(l.left * k, l.top * k, l.right * k, l.bottom * k, l.text) }
    }

    fun displayName(uri: Uri): String {
        if (uri.scheme == "file") return File(uri.path!!).name
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document"
    }

    /** Starts translating a document/PDF/image at [uri]. Returns the job id. */
    fun translateFile(uri: Uri, overrideName: String? = null, saveAsPdf: Boolean? = null): String {
        val id = context.app.library.newId()
        val name = overrideName ?: displayName(uri)
        val flow = MutableStateFlow(JobState(id, name))
        states[id] = flow
        val cancel = AtomicBoolean(false); cancels[id] = cancel
        running[id] = context.app.scope.launch {
            val timing = app.carnet.diag.Diag.timing("translate.document")
            try {
                val lib = context.app.library
                val dir = lib.dir(id)
                val ext = name.substringAfterLast('.', "").lowercase().ifEmpty { guessExt(uri) }
                val src = File(dir, "source.$ext")
                withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { i -> src.outputStream().use { i.copyTo(it) } } }
                val settings = context.app.settings.flow.first()
                val sample = withContext(Dispatchers.IO) { DocSample.of(src) }
                val englishName = name.substringBeforeLast('.')
                fun progress(stage: String) = object : Progress {
                    override fun update(done: Int, total: Int) {
                        val st = flow.value
                        val elapsed = (System.currentTimeMillis() - st.startedAt) / 1000.0
                        val eta = if (done in 1 until total) ((elapsed / done) * (total - done)).toInt() else null
                        flow.value = st.copy(stage = stage, done = done, total = total, etaSeconds = eta)
                    }
                    override fun cancelled() = cancel.get()
                }
                flow.value = flow.value.copy(stage = "Reading")
                val resultName: String
                val meta = HashMap<String, String>()
                when {
                    ext == "pdf" -> {
                        val out = File(dir, "result.pdf")
                        val stats = context.app.engine.exclusive(sample) { tr ->
                            out.outputStream().use { PdfTranslator(AndroidFonts(context)).translate(src, it, tr, { cancel.get() }, ocr = { page -> ocrPage(src, page) }) { d, t -> progress("Translating").update(d, t) } }
                        }
                        if (stats.scanned) {
                            // No text layer: OCR each page image instead.
                            translateScannedPdf(src, out, flow, cancel)
                        }
                        meta["pages"] = stats.pages.toString()
                        resultName = out.name
                    }
                    ext in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif") -> {
                        flow.value = flow.value.copy(stage = "Reading text", total = 3, done = 1)
                        val bmp = context.app.scanner.load(src)
                        val page = context.app.scanner.scan(bmp) { st -> flow.value = flow.value.copy(stage = st.replaceFirstChar { it.uppercase() }) }
                        val out = File(dir, "result.jpg")
                        withContext(Dispatchers.IO) { out.outputStream().use { page.translated.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
                        File(dir, "french.txt").writeText(page.frenchText); File(dir, "english.txt").writeText(page.englishText)
                        meta["pages"] = "1"
                        resultName = out.name
                        lib.put(LibraryItem(id, ItemType.SCAN, englishName, System.currentTimeMillis(), src.name, resultName, meta))
                        flow.value = flow.value.copy(stage = "Done", done = 3, total = 3, finished = true, resultId = id)
                        return@launch
                    }
                    else -> {
                        val fmt = DocFormat.fromName(src.name) ?: throw IllegalArgumentException("This file type isn't supported yet: .$ext")
                        var out = File(dir, "result.$ext")
                        val stats = context.app.engine.exclusive(sample) { tr ->
                            out.outputStream().use { o ->
                                src.inputStream().use { i ->
                                    Documents.translate(src.name, i, o, tr, TranslateOptions(targetLanguage = DocSample.targetTag(tr.target, settings.spelling), bilingual = settings.bilingual), progress("Translating"))
                                }
                            }
                        }
                        // Old .doc files come back as .docx (and mislabelled files as what they really are).
                        stats.outputExtension?.takeIf { it != ext }?.let { e -> File(dir, "result.$e").also { out.renameTo(it); out = it } }
                        meta["paragraphs"] = stats.paragraphs.toString()
                        meta["target"] = context.app.engine.resolve(sample).substringAfter('-')
                        resultName = if ((saveAsPdf ?: (settings.saveAs == "pdf")) && fmt != DocFormat.TXT) {
                            flow.value = flow.value.copy(stage = "Making PDF")
                            val pdf = File(dir, "result.pdf")
                            Converters.toPdf(context, out, pdf)
                            pdf.name
                        } else out.name
                    }
                }
                timing.tag("type", ext).measure("bytes", src.length()).measure("paragraphs", meta["paragraphs"]?.toIntOrNull() ?: 0).measure("pages", meta["pages"]?.toIntOrNull() ?: 0).ok()
                lib.put(LibraryItem(id, ItemType.DOCUMENT, englishName, System.currentTimeMillis(), src.name, resultName, meta))
                flow.value = flow.value.copy(stage = "Done", done = flow.value.total, finished = true, resultId = id)
            } catch (e: Throwable) {
                timing.fail(e)
                val msg = if (e is InterruptedException || cancel.get()) "Cancelled" else app.carnet.diag.Diag.friendly(e, "translate this file")
                if (!cancel.get()) app.carnet.diag.Diag.report(e, "translate.document", mapOf("type" to name.substringAfterLast('.', "?").lowercase()))
                flow.value = flow.value.copy(error = msg, finished = true)
                context.app.library.dir(id).takeIf { context.app.library.get(id) == null }?.deleteRecursively()
            } finally {
                running.remove(id)
            }
        }
        return id
    }

    private suspend fun translateScannedPdf(src: File, out: File, flow: MutableStateFlow<JobState>, cancel: AtomicBoolean) {
        val pages = ArrayList<Bitmap>()
        withContext(Dispatchers.IO) {
            PdfRenderer(ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
                for (i in 0 until r.pageCount) {
                    r.openPage(i).use { p ->
                        val scale = 2f // ~144 dpi
                        val bmp = Bitmap.createBitmap((p.width * scale).toInt(), (p.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        pages.add(bmp)
                    }
                }
            }
        }
        val results = ArrayList<Bitmap>()
        for ((i, bmp) in pages.withIndex()) {
            if (cancel.get()) throw InterruptedException()
            flow.value = flow.value.copy(stage = "Reading page ${i + 1}", done = i, total = pages.size)
            results.add(context.app.scanner.scan(bmp).translated)
        }
        Converters.imagesToPdf(results, out)
    }

    private fun guessExt(uri: Uri): String = when (context.contentResolver.getType(uri)) {
        "application/pdf" -> "pdf"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
        "application/vnd.oasis.opendocument.text" -> "odt"
        "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"
        "text/plain" -> "txt"
        else -> "bin"
    }

    /** Generic job wrapper for tools (conversions, PDF utilities). */
    fun run(title: String, block: suspend (report: (String, Int, Int) -> Unit, cancel: AtomicBoolean) -> LibraryItem): String {
        val id = context.app.library.newId()
        val flow = MutableStateFlow(JobState(id, title)); states[id] = flow
        val cancel = AtomicBoolean(false); cancels[id] = cancel
        running[id] = context.app.scope.launch {
            try {
                val item = block({ stage, d, t -> flow.value = flow.value.copy(stage = stage, done = d, total = t) }, cancel)
                context.app.library.put(item)
                flow.value = flow.value.copy(stage = "Done", finished = true, resultId = item.id, done = flow.value.total)
            } catch (e: Throwable) {
                flow.value = flow.value.copy(error = if (cancel.get()) "Cancelled" else app.carnet.diag.Diag.friendly(e, "finish this"), finished = true)
            } finally { running.remove(id) }
        }
        return id
    }

}
