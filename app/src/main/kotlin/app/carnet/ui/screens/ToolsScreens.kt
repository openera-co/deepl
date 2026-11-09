package app.carnet.ui.screens

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import app.carnet.app
import app.carnet.convert.Converters
import app.carnet.core.doc.Documents
import app.carnet.core.doc.Progress
import app.carnet.core.doc.TranslateOptions
import app.carnet.data.ItemType
import app.carnet.data.LibraryItem
import app.carnet.pdf.AndroidFonts
import app.carnet.pdf.PdfTools
import app.carnet.pdf.PdfTranslator
import app.carnet.ui.Btn
import app.carnet.ui.Card
import app.carnet.ui.Divider
import app.carnet.ui.FileBadge
import app.carnet.ui.Ic
import app.carnet.ui.IconBtn
import app.carnet.ui.Screen
import app.carnet.ui.SectionLabel
import app.carnet.ui.Segmented
import app.carnet.ui.SettingRow
import app.carnet.ui.Toggle
import app.carnet.ui.TopBar
import app.carnet.ui.Type
import app.carnet.ui.badgeColors
import app.carnet.ui.pal
import app.carnet.ui.tap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class Tool(val id: String, val label: String, val from: String, val to: String, val multi: Boolean = false, val translatable: Boolean = true, val icon: ImageVector? = null)

val CONVERSIONS = listOf(
    Tool("word2pdf", "Word to PDF", "DOCX", "PDF"), Tool("pdf2word", "PDF to Word", "PDF", "DOCX"),
    Tool("pptx2pdf", "PowerPoint to PDF", "PPTX", "PDF"), Tool("xlsx2pdf", "Excel to PDF", "XLSX", "PDF"),
    Tool("img2pdf", "Images to PDF", "JPG", "PDF", multi = true, translatable = false), Tool("pdf2img", "PDF to images", "PDF", "JPG", translatable = false),
    Tool("odt2docx", "ODT to Word", "ODT", "DOCX"), Tool("docx2odt", "Word to ODT", "DOCX", "ODT"),
)
val PDF_TOOLS = listOf(
    Tool("merge", "Merge", "PDF", "PDF", multi = true, translatable = false, icon = Ic.Merge), Tool("split", "Split", "PDF", "PDF", translatable = false, icon = Ic.Split),
    Tool("compress", "Compress", "PDF", "PDF", translatable = false, icon = Ic.Compress), Tool("rotate", "Rotate", "PDF", "PDF", translatable = false, icon = Ic.Rotate),
    Tool("reorder", "Reorder", "PDF", "PDF", translatable = false, icon = Ic.Tools), Tool("numbers", "Page numbers", "PDF", "PDF", translatable = false, icon = Ic.Numbers),
)

@Composable
fun ToolsScreen(nav: NavHostController) {
    Screen {
        TopBar("Tools", onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Convert")
            CONVERSIONS.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { t ->
                        Column(Modifier.weight(1f).height(76.dp).clip(RoundedCornerShape(16.dp)).background(pal.surface).tap { nav.navigate("convert/${t.id}") }.padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically) { Badge(t.from); Icon(Ic.Arrow, null, tint = pal.muted, modifier = Modifier.padding(horizontal = 6.dp).size(14.dp)); Badge(t.to) }
                            Text(t.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)
                        }
                    }
                }
            }
            SectionLabel("PDF")
            PDF_TOOLS.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { t ->
                        Column(Modifier.weight(1f).height(80.dp).clip(RoundedCornerShape(16.dp)).background(pal.surface).tap { nav.navigate("convert/${t.id}") },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(t.icon ?: Ic.File, null, tint = pal.accent, modifier = Modifier.size(22.dp)); Spacer(Modifier.height(8.dp))
                            Text(t.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).background(pal.surface).tap { nav.navigate("scan") }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(pal.accentSoft), contentAlignment = Alignment.Center) { Icon(Ic.Camera, null, tint = pal.accent, modifier = Modifier.size(20.dp)) }
                Text("Scan to PDF", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink, modifier = Modifier.weight(1f).padding(start = 12.dp))
                Icon(Ic.Chevron, null, tint = pal.faint, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun Badge(t: String) {
    val (bg, fg) = badgeColors(t)
    Text(t, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 3.dp))
}

@Composable
fun ConvertScreen(nav: NavHostController, toolId: String) {
    val ctx = LocalContext.current
    val tool = (CONVERSIONS + PDF_TOOLS).first { it.id == toolId }
    val files = remember { mutableStateListOf<Pair<Uri, String>>() }
    var translate by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf(1) }
    var rotation by remember { mutableStateOf(0) }
    var pages by remember { mutableStateOf("") }
    val mimes = when (tool.from) {
        "PDF" -> arrayOf("application/pdf"); "JPG" -> arrayOf("image/*")
        "DOCX" -> arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        "PPTX" -> arrayOf("application/vnd.openxmlformats-officedocument.presentationml.presentation")
        "XLSX" -> arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        "ODT" -> arrayOf("application/vnd.oasis.opendocument.text")
        else -> arrayOf("*/*")
    }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) { files.clear(); files.add(u to ctx.app.jobs.displayName(u)) } }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { us -> us.forEach { files.add(it to ctx.app.jobs.displayName(it)) } }
    fun pick() = if (tool.multi) pickMany.launch(mimes) else pickOne.launch(mimes)

    Screen {
        TopBar(tool.label, onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(20.dp)).background(pal.surface).tap { pick() }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                FileBadge(tool.from, size = 76.dp, fontSize = 14)
                Icon(Ic.Arrow, null, tint = pal.muted, modifier = Modifier.padding(horizontal = 22.dp).size(28.dp))
                FileBadge(tool.to, size = 76.dp, fontSize = 14)
            }
            Card {
                if (files.isEmpty()) SettingRow(if (tool.multi) "Choose files" else "Choose file", onClick = { pick() })
                files.forEachIndexed { i, (u, name) ->
                    if (i > 0) Divider()
                    Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        FileBadge(name.substringAfterLast('.', tool.from))
                        Text(name, style = Type.bodyStrong, color = pal.ink, maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                        if (tool.multi) IconBtn(Ic.Close, "Remove", tint = pal.muted, size = 36.dp) { files.removeAt(i) }
                        else Btn("Change", kind = app.carnet.ui.BtnKind.Soft, height = 36.dp) { pick() }
                    }
                }
                if (tool.multi && files.isNotEmpty()) { Divider(); SettingRow("Add more", onClick = { pick() }) }
                when (tool.id) {
                    "compress" -> { Divider(); SettingRow("Quality", chevron = false) { Segmented(listOf("Small", "Balanced", "High"), quality, Modifier.width(220.dp)) { quality = it } } }
                    "rotate" -> { Divider(); SettingRow("Rotate", chevron = false) { Segmented(listOf("90°", "180°", "270°"), rotation, Modifier.width(200.dp)) { rotation = it } } }
                    "split", "reorder" -> {
                        Divider()
                        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (tool.id == "split") "Pages" else "New order", style = Type.body, fontWeight = FontWeight.Medium, color = pal.ink, modifier = Modifier.weight(1f))
                            Box(Modifier.width(150.dp)) {
                                if (pages.isEmpty()) Text(if (tool.id == "split") "All, or 1-3, 5" else "3, 1, 2", color = pal.faint, style = Type.body)
                                BasicTextField(pages, { pages = it }, textStyle = Type.body.copy(color = pal.ink), cursorBrush = SolidColor(pal.accent), singleLine = true)
                            }
                        }
                    }
                }
                if (tool.translatable) { Divider(); SettingRow("Translate to English", chevron = false, onClick = { translate = !translate }) { Toggle(translate) { translate = it } } }
            }
        }
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            Btn(when (tool.id) { "merge" -> "Merge"; "split" -> "Split"; "compress" -> "Compress"; "rotate" -> "Rotate"; "reorder" -> "Reorder"; "numbers" -> "Add numbers"; else -> "Convert" },
                Modifier.fillMaxWidth(), height = 54.dp, enabled = files.isNotEmpty() && (!tool.multi || files.size >= (if (tool.id == "merge") 2 else 1))) {
                val inputs = files.toList()
                val id = ctx.app.jobs.run(tool.label) { report, cancel -> runTool(ctx, tool, inputs, translate, quality, listOf(90, 180, 270)[rotation], pages, report, cancel) }
                nav.navigate("job/$id")
            }
        }
    }
}

private fun parseRanges(s: String, count: Int): List<IntRange>? {
    if (s.isBlank() || s.trim().equals("all", true)) return null
    return s.split(',').mapNotNull { p ->
        val t = p.trim()
        val m = Regex("^(\\d+)\\s*-\\s*(\\d+)$").find(t)
        when {
            m != null -> (m.groupValues[1].toInt() - 1).coerceIn(0, count - 1)..(m.groupValues[2].toInt() - 1).coerceIn(0, count - 1)
            t.toIntOrNull() != null -> (t.toInt() - 1).coerceIn(0, count - 1).let { it..it }
            else -> null
        }
    }
}

suspend fun runTool(
    ctx: Context, tool: Tool, inputs: List<Pair<Uri, String>>, translate: Boolean, quality: Int, rotation: Int, pages: String,
    report: (String, Int, Int) -> Unit, cancel: java.util.concurrent.atomic.AtomicBoolean,
): LibraryItem = withContext(Dispatchers.IO) {
    val lib = ctx.app.library
    val id = lib.newId()
    val dir = lib.dir(id)
    report("Reading", 0, 1)
    val local = inputs.mapIndexed { i, (u, name) ->
        val ext = name.substringAfterLast('.', tool.from.lowercase()).lowercase()
        File(dir, if (inputs.size == 1) "source.$ext" else "source-${i + 1}.$ext").also { f -> ctx.contentResolver.openInputStream(u)!!.use { it.copyTo(f.outputStream()) } }
    }
    var input = local.first()
    if (translate) {
        report("Translating", 0, 1)
        val out = File(dir, "translated.${input.extension}")
        ctx.app.engine.exclusive(app.carnet.jobs.DocSample.of(input)) { tr ->
            val prog = object : Progress { override fun update(done: Int, total: Int) = report("Translating", done, total); override fun cancelled() = cancel.get() }
            if (input.extension.equals("pdf", true)) out.outputStream().use { PdfTranslator(AndroidFonts(ctx)).translate(input, it, tr, { cancel.get() }) { d, t -> prog.update(d, t) } }
            else out.outputStream().use { o -> input.inputStream().use { i -> Documents.translate(input.name, i, o, tr, TranslateOptions(targetLanguage = app.carnet.jobs.DocSample.targetTag(tr.target, "UK")), prog) } }
        }
        input = out
    }
    report(tool.label, 1, 2)
    val base = inputs.first().second.substringBeforeLast('.')
    val resultName: String
    when (tool.id) {
        "word2pdf", "pptx2pdf", "xlsx2pdf" -> { Converters.toPdf(ctx, input, File(dir, "result.pdf")); resultName = "result.pdf" }
        "pdf2word", "odt2docx" -> { Converters.toDocx(ctx, input, File(dir, "result.docx")); resultName = "result.docx" }
        "docx2odt" -> { Converters.toOdt(ctx, input, File(dir, "result.odt")); resultName = "result.odt" }
        "img2pdf" -> { Converters.imagesToPdf(local.map { BitmapFactory.decodeFile(it.path) }, File(dir, "result.pdf")); resultName = "result.pdf" }
        "pdf2img" -> {
            val imgs = Converters.pdfToImages(input, File(dir, "pages"))
            ZipOutputStream(File(dir, "result.zip").outputStream()).use { z -> for (f in imgs) { z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() } }
            resultName = "result.zip"
        }
        "merge" -> { File(dir, "result.pdf").outputStream().use { PdfTools.merge(local, it) }; resultName = "result.pdf" }
        "split" -> {
            val n = PdfTools.pageCount(input)
            val ranges = parseRanges(pages, n)
            val parts = ArrayList<File>()
            PdfTools.split(input, ranges) { i -> File(dir, "part-${i + 1}.pdf").also { parts.add(it) }.outputStream() }
            ZipOutputStream(File(dir, "result.zip").outputStream()).use { z -> for (f in parts) { z.putNextEntry(ZipEntry("$base-${f.name}")); f.inputStream().use { it.copyTo(z) }; z.closeEntry() } }
            resultName = if (parts.size == 1) parts.first().name else "result.zip"
        }
        "compress" -> {
            val q = listOf(0.45f, 0.65f, 0.82f)[quality]; val side = listOf(1200, 1600, 2400)[quality]
            Converters.compressPdf(input, File(dir, "result.pdf"), q, side); resultName = "result.pdf"
        }
        "rotate" -> { File(dir, "result.pdf").outputStream().use { PdfTools.rotate(input, it, rotation) }; resultName = "result.pdf" }
        "reorder" -> {
            val n = PdfTools.pageCount(input)
            val order = pages.split(',').mapNotNull { it.trim().toIntOrNull()?.minus(1)?.takeIf { p -> p in 0 until n } }.ifEmpty { (0 until n).toList() }
            File(dir, "result.pdf").outputStream().use { PdfTools.reorder(input, it, order) }; resultName = "result.pdf"
        }
        "numbers" -> { File(dir, "result.pdf").outputStream().use { PdfTools.pageNumbers(input, it, AndroidFonts(ctx)) }; resultName = "result.pdf" }
        else -> error("Unknown tool")
    }
    report("Done", 2, 2)
    LibraryItem(id, ItemType.CONVERSION, base + (if (translate) " (English)" else ""), System.currentTimeMillis(), input.name, resultName,
        mapOf("tool" to tool.id, "pages" to (if (resultName.endsWith(".pdf")) Converters.pageCount(File(dir, resultName)).toString() else "")).filterValues { it.isNotEmpty() })
}
