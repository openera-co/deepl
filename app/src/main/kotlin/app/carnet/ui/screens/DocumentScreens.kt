package app.carnet.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import app.carnet.app
import app.carnet.convert.Converters
import app.carnet.data.AppSettings
import app.carnet.ui.Btn
import app.carnet.ui.BtnKind
import app.carnet.ui.Card
import app.carnet.ui.Divider
import app.carnet.ui.FileBadge
import app.carnet.ui.Handoff
import app.carnet.ui.Ic
import app.carnet.ui.IconBtn
import app.carnet.ui.ProgressBar
import app.carnet.ui.Screen
import app.carnet.ui.Segmented
import app.carnet.ui.SettingRow
import app.carnet.ui.Toggle
import app.carnet.ui.TopBar
import app.carnet.ui.Type
import app.carnet.ui.pal
import app.carnet.ui.tap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

val SUPPORTED = listOf("DOCX", "PDF", "PPTX", "XLSX", "ODT", "TXT", "JPG", "PNG")
val PICK_TYPES = arrayOf(
    "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet", "application/vnd.oasis.opendocument.presentation",
    "text/plain", "text/markdown", "text/csv", "application/x-subrip", "text/html", "image/*", "application/octet-stream",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DocumentScreen(nav: NavHostController, settings: AppSettings, auto: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var asPdf by remember { mutableStateOf(settings.saveAs == "pdf") }
    var bilingual by remember { mutableStateOf(settings.bilingual) }
    fun start(uri: Uri) {
        scope.launch { ctx.app.settings.update { it.copy(bilingual = bilingual, saveAs = if (asPdf) "pdf" else "same") } }
        val id = ctx.app.jobs.translateFile(uri, saveAsPdf = asPdf)
        nav.navigate("job/$id")
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) start(uri) }
    LaunchedEffect(auto) { if (auto && Handoff.files.isNotEmpty()) { val f = Handoff.files.first(); Handoff.files = emptyList(); start(f) } }
    Screen {
        TopBar("Translate a document", onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(
                Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(20.dp)).background(pal.accentSoft)
                    .border(1.5.dp, pal.accent.copy(alpha = 0.4f), RoundedCornerShape(20.dp)).tap(haptic = true) { pick.launch(PICK_TYPES) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(pal.accent), contentAlignment = Alignment.Center) { Icon(Ic.Add, null, tint = pal.accentInk, modifier = Modifier.size(26.dp)) }
                Spacer(Modifier.height(12.dp))
                Text("Choose file", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = pal.accent)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SUPPORTED.forEach { t ->
                    val (bg, fg) = app.carnet.ui.badgeColors(t)
                    Text(t, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 9.dp, vertical = 5.dp))
                }
            }
            Card {
                SettingRow("Save as", chevron = false, onClick = null) {
                    Segmented(listOf("Same format", "PDF"), if (asPdf) 1 else 0, Modifier.width(200.dp)) { asPdf = it == 1 }
                }
                Divider()
                SettingRow("Bilingual copy", chevron = false, onClick = { bilingual = !bilingual }) { Toggle(bilingual) { bilingual = it } }
            }
        }
    }
}

@Composable
fun JobScreen(nav: NavHostController, id: String) {
    val ctx = LocalContext.current
    val flow = ctx.app.jobs.state(id) ?: run { nav.popBackStack(); return }
    val st by flow.collectAsState()
    LaunchedEffect(st.finished, st.resultId) {
        if (st.finished && st.resultId != null) {
            val item = ctx.app.library.get(st.resultId!!)
            val route = if (item?.type == app.carnet.data.ItemType.SCAN) "scanResult/${st.resultId}" else "docResult/${st.resultId}"
            nav.navigate(route) { popUpTo("job/$id") { inclusive = true } }
        }
    }
    val scan = rememberInfiniteTransition(label = "scan")
    val y by scan.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "y")
    Screen {
        TopBar(onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(196.dp, 256.dp).shadow(12.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(pal.surface).padding(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    val lines = listOf(0.7f, 1f, 0.92f, 0.96f, 0.6f, 0.45f, 1f, 0.88f, 0.94f, 0.8f)
                    lines.forEachIndexed { i, w ->
                        val done = st.fraction > i / lines.size.toFloat()
                        Box(Modifier.fillMaxWidth(w).height(if (i == 0 || i == 5) 9.dp else 6.dp).clip(RoundedCornerShape(3.dp))
                            .background(if (done) pal.accent.copy(alpha = if (i == 0 || i == 5) 1f else 0.45f) else pal.line))
                    }
                }
                if (!st.finished) Box(Modifier.fillMaxWidth().offset(y = (216 * y).dp).height(2.dp).background(pal.accent))
            }
            Spacer(Modifier.height(32.dp))
            Text(st.title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = pal.ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            AnimatedContent(st.error ?: st.stage, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "stage") { s ->
                Text(s, style = Type.body, color = if (st.error != null) pal.danger else pal.muted, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(28.dp))
            ProgressBar(if (st.total > 0) st.fraction else 0.05f)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("${(st.fraction * 100).toInt()}%", style = Type.small, color = pal.muted, modifier = Modifier.weight(1f))
                st.etaSeconds?.let { Text(if (it < 60) "About $it s left" else "About ${it / 60} min left", style = Type.small, color = pal.muted) }
            }
        }
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 24.dp)) {
            if (st.finished && st.error != null) Btn("Back", Modifier.fillMaxWidth(), kind = BtnKind.Secondary) { nav.popBackStack() }
            else Btn("Cancel", Modifier.fillMaxWidth(), kind = BtnKind.Secondary) { ctx.app.jobs.cancel(id); nav.popBackStack() }
        }
    }
}

@Composable
fun DocResultScreen(nav: NavHostController, id: String) {
    val ctx = LocalContext.current
    val item = ctx.app.library.get(id) ?: run { nav.popBackStack(); return }
    val dir = ctx.app.library.dir(id)
    val source = File(dir, item.sourceName ?: "")
    val result = File(dir, item.resultName ?: "")
    var tab by remember { mutableStateOf(1) }
    var page by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    // Previews: PDFs render directly; other formats are converted to PDF once and cached.
    val previewSrc by produceState<File?>(null) { value = withContext(Dispatchers.IO) { preview(ctx, source, File(dir, "preview-src.pdf")) } }
    val previewOut by produceState<File?>(null) { value = withContext(Dispatchers.IO) { preview(ctx, result, File(dir, "preview-out.pdf")) } }
    val current = if (tab == 0) previewSrc else previewOut
    val pageCount by produceState(0, current) { value = withContext(Dispatchers.IO) { current?.let { Converters.pageCount(it) } ?: 0 } }
    val bitmap by produceState<Bitmap?>(null, current, page) { value = withContext(Dispatchers.IO) { current?.let { Converters.renderPage(it, page, 1200) } } }
    Screen {
        TopBar(onBack = { nav.popBackStack() }, center = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                FileBadge(result.extension, size = 24.dp, fontSize = 9); Spacer(Modifier.width(8.dp))
                Text(item.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = pal.ink, maxLines = 1)
            }
        })
        Segmented(listOf("Original", "English"), tab, Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp)) { tab = it; page = 0 }
        Box(Modifier.weight(1f).fillMaxWidth().background(pal.surfaceAlt), contentAlignment = Alignment.Center) {
            AnimatedContent(bitmap, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) }, label = "page") { b ->
                if (b == null) Dots(pal.accent)
                else Image(b.asImageBitmap(), "Page ${page + 1}", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(16.dp).shadow(6.dp, RoundedCornerShape(2.dp)))
            }
        }
        Row(Modifier.fillMaxWidth().background(pal.surface).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Back, "Previous page", enabled = page > 0) { page-- }
            Text("${page + 1} / ${pageCount.coerceAtLeast(1)}", fontWeight = FontWeight.SemiBold, color = pal.ink, fontSize = 14.sp)
            IconBtn(Ic.Chevron, "Next page", enabled = page < pageCount - 1) { page++ }
            Spacer(Modifier.weight(1f))
            IconBtn(Ic.Share, "Share", border = true) { scope.launch { exportFile(ctx, result, item.title) } }
            Spacer(Modifier.width(8.dp))
            Btn("Save", icon = Ic.Save) { scope.launch { exportFile(ctx, result, item.title) } }
        }
    }
}

private fun preview(ctx: android.content.Context, f: File, cache: File): File? = runCatching {
    if (!f.exists()) return null
    if (f.extension.equals("pdf", true)) return f
    if (!cache.exists()) Converters.toPdf(ctx, f, cache)
    cache
}.getOrNull()
