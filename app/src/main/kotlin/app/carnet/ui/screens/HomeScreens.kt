package app.carnet.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import app.carnet.app
import app.carnet.data.AppSettings
import app.carnet.data.ItemType
import app.carnet.data.LibraryItem
import app.carnet.engine.Engine
import app.carnet.models.ModelStatus
import app.carnet.ui.BottomNav
import app.carnet.ui.Btn
import app.carnet.ui.BtnKind
import app.carnet.ui.Card
import app.carnet.ui.Divider
import app.carnet.ui.Handoff
import app.carnet.ui.Ic
import app.carnet.ui.IconBtn
import app.carnet.ui.ListItemRow
import app.carnet.ui.Screen
import app.carnet.ui.TopBar
import app.carnet.ui.Type
import app.carnet.ui.openItem
import app.carnet.ui.pal
import app.carnet.ui.tab
import app.carnet.ui.tap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.animation.animateContentSize
import app.carnet.ui.enter
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// ------------------------------------------------------------------------------------------ Setup

@Composable
fun SetupScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val engine by ctx.app.engine.state.collectAsState()
    val models by ctx.app.models.status.collectAsState()
    val scope = rememberCoroutineScope()
    Screen {
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)).background(pal.accent), contentAlignment = Alignment.Center) {
                Icon(Ic.Logo, null, tint = Color.White, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text("Carnet", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = pal.ink)
            Text("French ⇄ English", style = Type.body, color = pal.muted, fontSize = 16.sp)
        }
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GetTranslationCard(onShare = { nav.navigate("share") })
            Btn("Continue", Modifier.fillMaxWidth(), height = 54.dp) {
                scope.launch { ctx.app.settings.update { it.copy(setupDone = true) } }
                nav.navigate("home") { popUpTo("setup") { inclusive = true } }
            }
        }
    }
}

@Composable
fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.bodyStrong, color = pal.ink, modifier = Modifier.weight(1f))
        if (ok) Icon(Ic.Check, null, tint = pal.good, modifier = Modifier.size(18.dp).padding(end = 2.dp))
        Text(value, style = Type.body, fontWeight = FontWeight.SemiBold, color = if (ok) pal.good else pal.muted)
    }
}

@Composable
fun ModelRow(title: String, size: String, st: ModelStatus, onGet: () -> Unit, onPause: () -> Unit, onDelete: (() -> Unit)? = null, about: String? = null,
             icon: androidx.compose.ui.graphics.vector.ImageVector? = null, updateBytes: Long = 0, onUpdate: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().then(if (about != null) Modifier.tap(scaleTo = 1f) { open = !open } else Modifier).padding(horizontal = 16.dp, vertical = 12.dp).animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(if (st == ModelStatus.Installed) pal.goodSoft else pal.accentSoft), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = if (st == ModelStatus.Installed) pal.good else pal.accent, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = Type.bodyStrong, color = pal.ink)
                Text(when (st) {
                    is ModelStatus.Downloading -> st.waiting ?: ("${formatSize(st.done)} of ${formatSize(st.total)}" + if (st.speed > 0) " · ${app.carnet.models.DownloadService.fmt(st.speed)}/s" else "")
                    is ModelStatus.Failed -> st.reason
                    ModelStatus.Verifying -> "Setting up…"
                    ModelStatus.Installed -> if (updateBytes > 0) "Update · ${formatSize(updateBytes)}" else size
                    else -> size
                }, style = Type.small, color = if (st is ModelStatus.Failed) pal.danger else pal.muted)
            }
            when (st) {
                ModelStatus.Installed -> when {
                    updateBytes > 0 && onUpdate != null -> Btn("Update", kind = BtnKind.Soft, height = 36.dp, onClick = onUpdate)
                    onDelete != null -> IconBtn(Ic.Delete, "Delete $title", tint = pal.muted, onClick = onDelete)
                    else -> Icon(Ic.Check, "Ready", tint = pal.good, modifier = Modifier.size(20.dp))
                }
                is ModelStatus.Downloading -> if (st.paused) Btn("Resume", kind = BtnKind.Soft, height = 36.dp, onClick = onGet) else IconBtn(Ic.Pause, "Pause", tint = pal.accent, bg = pal.accentSoft, size = 40.dp, onClick = onPause)
                ModelStatus.Verifying -> Dots(pal.accent)
                else -> IconBtn(Ic.Download, "Download $title", tint = pal.accent, bg = pal.accentSoft, size = 40.dp, onClick = onGet)
            }
        }
        if (st is ModelStatus.Downloading) { Spacer(Modifier.height(10.dp)); app.carnet.ui.ProgressBar(if (st.total > 0) st.done.toFloat() / st.total else 0f, height = 6.dp) }
        if (open && about != null) Text(about, style = Type.small, color = pal.muted, modifier = Modifier.padding(top = 8.dp))
    }
}

fun formatSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "${b / (1L shl 20)} MB"
    b >= 1L shl 10 -> "${b / (1L shl 10)} KB"
    else -> "$b B"
}

fun formatWhen(t: Long): String {
    val now = System.currentTimeMillis()
    val d = now - t
    val cal = java.util.Calendar.getInstance()
    val today = cal.apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }.timeInMillis
    return when {
        t >= today -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
        t >= today - TimeUnit.DAYS.toMillis(1) -> "Yesterday"
        d < TimeUnit.DAYS.toMillis(6) -> SimpleDateFormat("EEEE", Locale.ENGLISH).format(Date(t))
        else -> SimpleDateFormat("d MMM", Locale.ENGLISH).format(Date(t))
    }
}

fun subtitle(item: LibraryItem): String {
    val w = formatWhen(item.created)
    return when (item.type) {
        ItemType.RECORDING -> "${(item.meta["duration"]?.toLongOrNull() ?: 0) / 60000} min · $w"
        ItemType.TEXT -> w
        ItemType.SCAN -> "${item.meta["pages"] ?: "1"} page · $w"
        else -> item.meta["pages"]?.let { "$it pages · $w" } ?: w
    }
}

// ------------------------------------------------------------------------------------------ Home

@Composable
fun HomeScreen(nav: NavHostController, settings: AppSettings) {
    val ctx = LocalContext.current
    val items by ctx.app.library.items.collectAsState()
    var text by remember { mutableStateOf("") }
    // One tap: pick a file, and the translated copy is on its way.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { Handoff.files = listOf(uri); nav.navigate("document?auto=true") }
    }
    Screen {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(pal.accent), contentAlignment = Alignment.Center) { Icon(Ic.Logo, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(10.dp))
            Text("Carnet", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = pal.ink, modifier = Modifier.weight(1f))
            IconBtn(Ic.Settings, "Settings") { nav.navigate("settings") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            UpdateBanner()
            GetTranslationCard(quality = settings.model, onShare = { nav.navigate("share") })
            // Hero: translate a file.
            Row(
                Modifier.enter(0).fillMaxWidth().height(104.dp).clip(RoundedCornerShape(22.dp)).background(pal.accent)
                    .tap(haptic = true, scaleTo = 0.97f) { pick.launch(PICK_TYPES) }.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(pal.accentInk.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(Ic.File, null, tint = pal.accentInk, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Translate a file", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = pal.accentInk)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (t in listOf("PDF", "Word", "PowerPoint", "Excel")) Text(t, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = pal.accentInk,
                            modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(pal.accentInk.copy(alpha = 0.16f)).padding(horizontal = 7.dp, vertical = 3.dp))
                    }
                }
                Icon(Ic.Arrow, null, tint = pal.accentInk, modifier = Modifier.size(22.dp))
            }
            Row(Modifier.enter(1), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeTile("Scan", Ic.Camera, Modifier.weight(1f)) { nav.navigate("scan") }
                HomeTile("Record", Ic.Mic, Modifier.weight(1f)) { nav.tab("record") }
                HomeTile("Tools", Ic.Tools, Modifier.weight(1f)) { nav.navigate("tools") }
            }
            Card(Modifier.enter(2)) {
                DirectionBar(settings, ctx.app.engine.resolve(text, if (settings.direction == "auto") null else settings.direction), Modifier.padding(horizontal = 16.dp))
                Divider()
                Column(Modifier.padding(16.dp)) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 84.dp)) {
                        if (text.isEmpty()) Text("Type or paste text", style = Type.input, color = pal.faint)
                        BasicTextField(text, { text = it }, textStyle = Type.input.copy(color = pal.ink), cursorBrush = SolidColor(pal.accent), modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBtn(Ic.Paste, "Paste", border = true) { paste(ctx)?.let { text = it } }
                        Spacer(Modifier.weight(1f))
                        Btn("Translate", height = 44.dp, icon = Ic.Arrow) { Handoff.text = text; nav.navigate("text") }
                    }
                }
            }
            if (items.isNotEmpty()) {
                Row(Modifier.enter(3).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = pal.ink, modifier = Modifier.weight(1f))
                    Text("See all", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.accent, modifier = Modifier.tap { nav.tab("library") }.padding(8.dp))
                }
                Column(Modifier.enter(3)) {
                    items.take(4).forEachIndexed { i, it ->
                        if (i > 0) Divider()
                        ListItemRow(it.badge, it.title, subtitle(it), onClick = { nav.openItem(it) })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        BottomNav("home") { nav.tab(it) }
    }
}

@Composable
private fun HomeTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(92.dp).clip(RoundedCornerShape(18.dp)).background(pal.surface).border(1.dp, pal.line, RoundedCornerShape(18.dp))
            .tap(haptic = true, scaleTo = 0.95f, onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(pal.accentSoft), contentAlignment = Alignment.Center) { Icon(icon, null, tint = pal.accent, modifier = Modifier.size(20.dp)) }
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)
    }
}

fun paste(ctx: Context): String? {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
}

fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Carnet", text))
}

fun shareText(ctx: Context, text: String) {
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
}

// ------------------------------------------------------------------------------------------ Text translation

@Composable
fun TextScreen(nav: NavHostController, settings: AppSettings) {
    val ctx = LocalContext.current
    val states by ctx.app.engine.states.collectAsState()
    var src by remember { mutableStateOf(Handoff.text) }
    var out by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val tts = rememberTts()
    val fixed = if (settings.direction == "auto") null else settings.direction
    val dir = ctx.app.engine.resolve(src, fixed)
    val engine = states[dir] ?: Engine.State.NoModel
    // Translate as you type (debounced); the model translates a sentence in ~0.1 s.
    LaunchedEffect(src, dir, engine) {
        if (src.isBlank()) { out = ""; return@LaunchedEffect }
        if (engine == Engine.State.NoModel) { out = ""; return@LaunchedEffect }
        delay(if (out.isEmpty()) 0 else 280)
        busy = true
        failed = null
        out = runCatching { ctx.app.engine.translate(src, dir) }.getOrElse { app.carnet.diag.Diag.report(it, "translate.text"); failed = app.carnet.diag.Diag.friendly(it, "translate"); out }
        busy = false
    }
    LaunchedEffect(Unit) { if (src.isEmpty()) focus.requestFocus() }
    Screen(Modifier.imePadding()) {
        TopBar(onBack = { nav.popBackStack() }, center = {
            DirectionBar(settings, dir, Modifier.width(300.dp), compact = true)
        }, actions = { Spacer(Modifier.width(8.dp)) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(langName(dir.substringBefore('-')).uppercase(), style = Type.section, color = pal.muted, modifier = Modifier.weight(1f))
                    AnimatedVisibility(src.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) { IconBtn(Ic.Close, "Clear", tint = pal.muted, size = 36.dp) { src = ""; focus.requestFocus() } }
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 90.dp)) {
                    if (src.isEmpty()) Text("Type or paste text", style = Type.input, color = pal.faint)
                    BasicTextField(src, { src = it }, textStyle = Type.input.copy(color = pal.ink), cursorBrush = SolidColor(pal.accent), modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp).focusRequester(focus))
                }
            }
            if (engine == Engine.State.NoModel && src.isNotBlank()) NeedsModelNote(dir) { nav.navigate("models") }
            AnimatedVisibility(src.isNotBlank() && engine != Engine.State.NoModel, enter = expandVertically(tween(220)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(pal.accent).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(langName(dir.substringAfter('-')).uppercase(), style = Type.section, color = pal.accentInk.copy(alpha = 0.75f), modifier = Modifier.weight(1f))
                        if (busy || engine != Engine.State.Ready) app.carnet.ui.screens.Dots(pal.accentInk)
                    }
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        AnimatedContent(out, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(90)) }, label = "out") { t ->
                            Text(t.ifEmpty { " " }, style = Type.output, color = pal.accentInk)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        IconBtn(Ic.Listen, "Listen", tint = pal.accentInk) { tts.speak(out, dir.substringAfter('-')) }
                        IconBtn(Ic.Share, "Share", tint = pal.accentInk) { shareText(ctx, out) }
                        Spacer(Modifier.width(4.dp))
                        Box(Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(pal.accentInk).tap(haptic = true) {
                            copy(ctx, out); copied = true
                            scope.launch { delay(1400); copied = false }
                            scope.launch { saveText(ctx, src, out) }
                        }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                            AnimatedContent(copied, label = "copied") { c ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (c) Ic.Check else Ic.Copy, null, tint = pal.accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                                    Text(if (c) "Copied" else "Copy", color = pal.accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }
            (failed ?: if (engine == Engine.State.Failed) "Translation couldn't start. Restart the app, or download the model again in Settings." else null)?.let {
                Text(it, color = pal.danger, style = Type.small)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { if (src.isNotBlank() && out.isNotBlank()) ctx.app.scope.launch { saveText(ctx, src, out) } }
    }
}

suspend fun saveText(ctx: Context, fr: String, en: String) {
    val lib = ctx.app.library
    // One entry per distinct text: update the latest if it's the same session.
    val last = lib.items.value.firstOrNull { it.type == ItemType.TEXT }
    val id = if (last != null && fr.startsWith(last.meta["french"]?.take(12) ?: "\u0000") && System.currentTimeMillis() - last.created < 600_000) last.id else lib.newId()
    lib.put(LibraryItem(id, ItemType.TEXT, en.take(60).ifEmpty { fr.take(60) }, System.currentTimeMillis(), meta = mapOf("french" to fr, "english" to en)))
}

class Tts(ctx: Context) {
    private var ready = false
    private val tts = TextToSpeech(ctx.applicationContext) { ready = it == TextToSpeech.SUCCESS }
    fun speak(text: String, lang: String = "en") {
        if (!ready) return
        tts.language = if (lang == "fr") Locale.FRANCE else Locale.UK
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "carnet")
    }
    fun shutdown() = tts.shutdown()
}

@Composable
fun rememberTts(): Tts {
    val ctx = LocalContext.current
    val tts = remember { Tts(ctx) }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }
    return tts
}
