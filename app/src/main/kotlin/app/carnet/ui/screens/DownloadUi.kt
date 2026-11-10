package app.carnet.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.carnet.app
import app.carnet.data.AppSettings
import app.carnet.engine.Engine
import app.carnet.models.DownloadService
import app.carnet.models.ModelStatus
import app.carnet.ui.Btn
import app.carnet.ui.BtnKind
import app.carnet.ui.Card
import app.carnet.ui.Ic
import app.carnet.ui.IconBtn
import app.carnet.ui.ProgressBar
import app.carnet.ui.Type
import app.carnet.ui.pal
import app.carnet.ui.tap
import app.carnet.update.UpdateState
import kotlinx.coroutines.launch

/** Asks for the notification permission (Android 13+) right before a background download starts. */
@Composable
fun rememberStartDownload(start: () -> Unit): () -> Unit {
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }
    val ctx = LocalContext.current
    return {
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            ask.launch(Manifest.permission.POST_NOTIFICATIONS) else start()
    }
}

/**
 * The one button: downloads everything needed to translate both ways, with live progress,
 * size and speed. Hidden once installed.
 */
@Composable
fun GetTranslationCard(modifier: Modifier = Modifier, quality: String = "best", onShare: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val mm = ctx.app.models
    val status by mm.status.collectAsState()
    val catalog by mm.catalogFlow.collectAsState()
    val pack = remember(catalog, quality) { mm.pack(quality) }
    val missing = pack.filter { status[it.id] !is ModelStatus.Installed }
    val start = rememberStartDownload { mm.downloadRecommended(quality) }
    AnimatedVisibility(missing.isNotEmpty() || pack.isEmpty(), modifier, enter = expandVertically() + fadeIn(), exit = shrinkVertically(tween(260)) + fadeOut()) {
        Card(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            if (pack.isEmpty()) {
                Text("Go online once to set up", style = Type.bodyStrong, color = pal.ink)
                if (onShare != null) { Spacer(Modifier.height(12.dp)); Btn("Get from another phone", Modifier.fillMaxWidth(), kind = BtnKind.Soft, icon = Ic.Qr, onClick = onShare) }
                return@Card
            }
            val total = missing.sumOf { it.size }
            val sts = missing.map { status[it.id] }
            val done = missing.zip(sts).sumOf { (sp, st) -> when (st) { is ModelStatus.Downloading -> st.done; ModelStatus.Verifying -> sp.size; else -> 0L } }
            val active = sts.any { it is ModelStatus.Downloading && !it.paused || it == ModelStatus.Verifying }
            val paused = !active && sts.any { it is ModelStatus.Downloading }
            val speed = sts.sumOf { (it as? ModelStatus.Downloading)?.speed ?: 0 }
            val waiting = sts.firstNotNullOfOrNull { (it as? ModelStatus.Downloading)?.waiting }
            val failed = sts.firstNotNullOfOrNull { (it as? ModelStatus.Failed)?.reason }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(pal.accentSoft), contentAlignment = Alignment.Center) { Icon(Ic.Download, null, tint = pal.accent, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("French ⇄ English", style = Type.bodyStrong, color = pal.ink)
                    AnimatedContent(when {
                        failed != null -> failed
                        active && waiting != null -> waiting
                        active && sts.any { it == ModelStatus.Verifying } && speed == 0L -> "Setting up…"
                        active || paused -> "${formatSize(done)} of ${formatSize(total)}" + (if (speed > 0) " · ${DownloadService.fmt(speed)}/s" + eta(total - done, speed) else "")
                        else -> formatSize(total)
                    }, transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(80)) }, label = "dl") { t ->
                        Text(t, style = Type.small, color = if (failed != null) pal.danger else pal.muted)
                    }
                }
                when {
                    active -> IconBtn(Ic.Pause, "Pause", tint = pal.accent, bg = pal.accentSoft, size = 40.dp) { missing.forEach { mm.pause(it.id) } }
                    else -> Btn(if (paused || failed != null) "Resume" else "Get", height = 40.dp, onClick = start)
                }
            }
            AnimatedVisibility(active || paused) {
                val f by animateFloatAsState(if (total > 0) done.toFloat() / total else 0f, tween(400), label = "p")
                Column { Spacer(Modifier.height(12.dp)); ProgressBar(f, height = 8.dp) }
            }
            if (onShare != null && !active) {
                Spacer(Modifier.height(4.dp))
                Text("Get from another phone", color = pal.accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.tap(onClick = onShare).padding(vertical = 8.dp))
            }
        }
    }
}

private fun eta(left: Long, speed: Long): String {
    if (speed <= 0) return ""
    val s = left / speed
    return when { s < 60 -> " · ${s}s left"; s < 3600 -> " · ${s / 60} min left"; else -> " · ${s / 3600} h left" }
}

/** "Carnet x.y is available" with one tap to download and install; progress while downloading. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val st by ctx.app.updater.state.collectAsState()
    val start = rememberStartDownload { (st as? UpdateState.Available)?.let { ctx.app.updater.download(it.info) } }
    AnimatedVisibility(st is UpdateState.Available || st is UpdateState.Downloading || st is UpdateState.Ready, modifier, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Card(padding = androidx.compose.foundation.layout.PaddingValues(16.dp), color = pal.accentSoft) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    when (val s = st) {
                        is UpdateState.Available -> {
                            Text("New version", style = Type.bodyStrong, color = pal.ink)
                            Text("${s.info.versionName} · ${formatSize(s.info.size)}", style = Type.small, color = pal.muted)
                        }
                        is UpdateState.Downloading -> {
                            Text("Updating", style = Type.bodyStrong, color = pal.ink)
                            Text("${formatSize(s.done)} of ${formatSize(s.total)}" + if (s.speed > 0) " · ${DownloadService.fmt(s.speed)}/s" else "", style = Type.small, color = pal.muted)
                        }
                        is UpdateState.Ready -> { Text("Ready to install", style = Type.bodyStrong, color = pal.ink) }
                        else -> {}
                    }
                }
                when (val s = st) {
                    is UpdateState.Available -> Btn("Update", height = 40.dp, onClick = start)
                    is UpdateState.Ready -> Btn("Install", height = 40.dp) { ctx.app.updater.install(s.file) }
                    is UpdateState.Downloading -> IconBtn(Ic.Close, "Cancel", tint = pal.accent, size = 40.dp) { ctx.app.updater.cancel() }
                    else -> {}
                }
            }
            (st as? UpdateState.Downloading)?.let { s ->
                Spacer(Modifier.height(12.dp)); ProgressBar(if (s.total > 0) s.done.toFloat() / s.total else 0f, height = 6.dp, track = pal.surface)
            }
        }
    }
}

fun langName(code: String) = if (code == "fr") "French" else "English"

/**
 * French ⇄ English. Shows the direction in use ([detected] under Auto); the arrow swaps and fixes the
 * direction, "Auto" goes back to detecting it from the text.
 */
@Composable
fun DirectionBar(settings: AppSettings, detected: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dir = if (settings.direction == "auto") detected else settings.direction
    val src = dir.substringBefore('-'); val tgt = dir.substringAfter('-')
    val turn by animateFloatAsState(if (dir == Engine.EN_FR) 180f else 0f, tween(260), label = "swap")
    fun set(d: String) = scope.launch { ctx.app.settings.update { it.copy(direction = d) } }
    Row(modifier.fillMaxWidth().height(if (compact) 40.dp else 44.dp), verticalAlignment = Alignment.CenterVertically) {
        AnimatedContent(src, label = "src", modifier = Modifier.weight(1f)) { Text(langName(it), style = Type.bodyStrong, color = pal.ink, fontSize = 14.sp) }
        Box(Modifier.size(40.dp).clip(CircleShape).background(pal.accentSoft).tap(haptic = true) { set(if (dir == Engine.FR_EN) Engine.EN_FR else Engine.FR_EN) }, contentAlignment = Alignment.Center) {
            Icon(Ic.Swap, "Swap languages", tint = pal.accent, modifier = Modifier.size(20.dp).rotate(turn))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            AnimatedContent(tgt, label = "tgt") { Text(langName(it), style = Type.bodyStrong, color = pal.ink, fontSize = 14.sp) }
        }
        Spacer(Modifier.width(10.dp))
        val auto = settings.direction == "auto"
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (auto) pal.accent else pal.surfaceAlt).tap { set("auto") }.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text("Auto", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (auto) pal.accentInk else pal.muted)
        }
    }
}

/** Shown where translation is needed but its model isn't installed yet. */
@Composable
fun NeedsModelNote(dir: String, onModels: () -> Unit) {
    Card(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Text("${langName(dir.substringBefore('-'))} → ${langName(dir.substringAfter('-'))} isn't downloaded yet", style = Type.bodyStrong, color = pal.ink)
        Spacer(Modifier.height(10.dp))
        Btn("Get it", Modifier.fillMaxWidth(), kind = BtnKind.Soft, icon = Ic.Download, onClick = onModels)
    }
}


/** One short line on what a model is for (shown when a row is tapped). */
fun aboutModel(m: app.carnet.models.ModelSpec): String = when {
    m.direction != null && m.quality == "fast" -> "Smaller and about twice as fast, a little less accurate."
    m.direction != null -> "Most accurate. Used for text, photos, documents and dictation."
    m.id == "htr-fr" -> "Reads handwritten French notes in photos."
    m.id == "handwriting" -> "Reads difficult handwriting and polishes hard sentences. Large and slower."
    m.id == "whisper-small" -> "Listens to a recording again after you stop, for a cleaner transcript."
    m.id == "whisper-turbo" -> "The most accurate cleanup of recordings. Slower."
    else -> ""
}

fun modelIcon(m: app.carnet.models.ModelSpec) = when {
    m.direction != null -> Ic.Translate
    m.id.startsWith("whisper") -> Ic.Mic
    else -> Ic.Pen
}

fun modelTitle(m: app.carnet.models.ModelSpec) = when {
    m.direction == "fr-en" -> "French → English"
    m.direction == "en-fr" -> "English → French"
    else -> m.title
}

const val ABOUT_PRINTED = "Reads printed text in photos and scans."
