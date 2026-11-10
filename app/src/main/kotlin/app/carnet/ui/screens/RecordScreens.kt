package app.carnet.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import app.carnet.app
import app.carnet.data.AppSettings
import app.carnet.data.ItemType
import app.carnet.nativeai.Whisper
import app.carnet.record.RecordService
import app.carnet.record.Recorder
import app.carnet.record.Segment
import app.carnet.ui.BottomNav
import app.carnet.ui.Btn
import app.carnet.ui.BtnKind
import app.carnet.ui.EmptyState
import app.carnet.ui.Ic
import app.carnet.ui.IconBtn
import app.carnet.ui.ListItemRow
import app.carnet.ui.ProgressBar
import app.carnet.ui.Screen
import app.carnet.ui.Segmented
import app.carnet.ui.TopBar
import app.carnet.ui.Type
import app.carnet.ui.pal
import app.carnet.ui.tab
import app.carnet.ui.tap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

fun clock(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

@Composable
fun RecordScreen(nav: NavHostController, settings: AppSettings) {
    val ctx = LocalContext.current
    val st by Recorder.state.collectAsState()
    var show by remember { mutableStateOf(2) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = it[Manifest.permission.RECORD_AUDIO] == true }
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(st.recording, st.paused) { while (st.recording && !st.paused) { now = st.elapsedMs; delay(250) } }
    val listState = rememberLazyListState()
    LaunchedEffect(st.segments.size, st.partial) { if (st.segments.isNotEmpty()) listState.animateScrollToItem(st.segments.size) }
    val recordings by ctx.app.library.items.collectAsState()

    fun start() {
        if (!granted) { ask.launch(arrayOf(Manifest.permission.RECORD_AUDIO) + if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()); return }
        RecordService.start(ctx)
        Recorder.start(ctx, "Dictation")
    }

    Screen {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Record", style = Type.title, color = pal.ink, modifier = Modifier.weight(1f))
            if (st.recording) {
                val pulse = rememberInfiniteTransition(label = "rec")
                val a by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
                Row(Modifier.clip(CircleShape).background(pal.dangerSoft).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).graphicsLayer { alpha = if (st.paused) 0.4f else a }.clip(CircleShape).background(Color(0xFFD92D20))); Spacer(Modifier.width(8.dp))
                    Text(clock(now), color = pal.danger, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
        if (!st.recording) {
            // Idle: big record button + previous recordings.
            Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    app.carnet.ui.PulseRings(Color(0xFFD92D20), 96.dp) {
                        Box(Modifier.size(96.dp).clip(CircleShape).background(Color(0xFFD92D20)).tap(haptic = true, scaleTo = 0.9f) { start() }, contentAlignment = Alignment.Center) {
                            Icon(Ic.Mic, "Start recording", tint = Color.White, modifier = Modifier.size(40.dp))
                        }
                    }
                    Text("Tap to record", fontWeight = FontWeight.SemiBold, color = pal.ink, fontSize = 16.sp)
                    if (!Recorder.available(ctx)) OfflineSpeechChip(Modifier.padding(top = 12.dp))
                }
                val recs = recordings.filter { it.type == ItemType.RECORDING }
                if (recs.isEmpty()) EmptyState(Ic.Mic, "No recordings yet")
                else LazyColumn {
                    itemsIndexed(recs, key = { _, it -> it.id }) { i, it ->
                        if (i > 0) app.carnet.ui.Divider()
                        ListItemRow("REC", it.title, subtitle(it), onClick = { nav.navigate("transcript/${it.id}") })
                    }
                }
            }
        } else {
            Segmented(listOf("Original", "Translation", "Both"), show, Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp)) { show = it }
            Box(Modifier.weight(1f).padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(pal.surface)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(st.segments) { _, s -> SegmentView(s, show) }
                    item {
                        if (st.partial.isNotEmpty()) Text(st.partial, color = pal.faint, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        if (st.needsSpeechModel) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                            Icon(Ic.Mic, null, tint = pal.muted, modifier = Modifier.size(28.dp))
                            Text(if (Recorder.lang == "en") "This phone can't turn English speech into text" else "This phone can't turn French speech into text",
                                color = pal.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                            Text("Audio is being recorded", style = Type.small, color = pal.muted)
                            OfflineSpeechChip(Modifier.padding(top = 14.dp))
                        }
                        st.error?.let { Text(it, color = pal.danger, style = Type.small) }
                    }
                }
            }
            // Live sound wave.
            app.carnet.ui.SoundWave(st.level, active = !st.paused, modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 20.dp),
                color = if (st.paused) pal.muted else pal.accent)
            Row(Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 44.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Ic.Mark, "Mark this moment", size = 52.dp, border = true, bg = pal.surface) { Recorder.mark() }
                Box(Modifier.size(76.dp).clip(CircleShape).background(Color(0xFFD92D20)).tap(haptic = true, scaleTo = 0.9f) {
                    scope.launch {
                        val id = Recorder.stop()
                        RecordService.stop(ctx)
                        if (id != null) {
                            if (settings.improveAfterStop) Whisper.improveInBackground(ctx, id)
                            nav.navigate("transcript/$id")
                        }
                    }
                }, contentAlignment = Alignment.Center) { Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(Color.White)) }
                IconBtn(if (st.paused) Ic.Play else Ic.Pause, if (st.paused) "Resume" else "Pause", size = 52.dp, border = true, bg = pal.surface) { if (st.paused) Recorder.resume() else Recorder.pause() }
            }
        }
        BottomNav("record") { nav.tab(it) }
    }
}

/**
 * One tap to get Carnet's own offline speech model (live captions on phones whose speech service
 * can't do it). Shows progress, and switches a running recording over as soon as it's ready.
 */
@Composable
fun OfflineSpeechChip(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val mm = ctx.app.models
    if (!Recorder.offlinePossible(ctx)) {
        Text("Audio only on this phone", style = Type.small, color = pal.muted, modifier = modifier); return
    }
    val status by mm.status.collectAsState()
    val s = status[Recorder.LIVE_MODEL]
    LaunchedEffect(s is app.carnet.models.ModelStatus.Installed) { if (s is app.carnet.models.ModelStatus.Installed) Recorder.goOffline() }
    if (s is app.carnet.models.ModelStatus.Installed) return
    val start = rememberStartDownload { mm.download(Recorder.LIVE_MODEL) }
    val size = remember { mm.specOrNull(Recorder.LIVE_MODEL)?.files?.sumOf { it.size } ?: 0L }
    val label = when (s) {
        is app.carnet.models.ModelStatus.Downloading -> if (s.total > 0) "Getting offline speech · ${s.done * 100 / s.total}%" else "Getting offline speech…"
        app.carnet.models.ModelStatus.Verifying -> "Setting up…"
        is app.carnet.models.ModelStatus.Failed -> "Couldn't download · Try again"
        else -> "Get offline speech · ${size / 1_000_000} MB"
    }
    Row(modifier.clip(CircleShape).background(pal.accent).tap { if (s !is app.carnet.models.ModelStatus.Downloading && s != app.carnet.models.ModelStatus.Verifying) start() }
        .padding(horizontal = 18.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.Download, null, tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun SegmentView(s: Segment, show: Int) {
    Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (s.marked) Text("● Marked", color = pal.warnInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (show != 1 && s.french.isNotEmpty()) Text(s.french, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = pal.ink)
        if (show != 0) s.english?.let { Text(it, fontSize = if (show == 1) 17.sp else 15.sp, lineHeight = 22.sp, color = if (show == 1) pal.ink else pal.accent) }
    }
}

@Composable
fun TranscriptScreen(nav: NavHostController, id: String) {
    val ctx = LocalContext.current
    val item = ctx.app.library.get(id) ?: run { nav.popBackStack(); return }
    val dir = ctx.app.library.dir(id)
    val improve by Whisper.progress.collectAsState()
    var segments by remember(improve[id]) { mutableStateOf(Recorder.loadTranscript(File(dir, "transcript.json"))) }
    val audio = File(dir, "audio.wav").takeIf { it.exists() && it.length() > 44 }
    val player = remember { MediaPlayer() }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    val duration = item.meta["duration"]?.toLongOrNull() ?: 0L
    val scope = rememberCoroutineScope()
    DisposableEffect(audio) {
        audio?.let { runCatching { player.setDataSource(it.path); player.prepare() } }
        onDispose { player.release() }
    }
    LaunchedEffect(playing) { while (playing) { pos = player.currentPosition.toLong(); if (!player.isPlaying) playing = false; delay(200) } }
    fun seek(ms: Long) { if (audio == null) return; player.seekTo(ms.toInt()); pos = ms; if (!player.isPlaying) { player.start(); playing = true } }
    Screen {
        TopBar(item.title, onBack = { nav.popBackStack() }, actions = {
            IconBtn(Ic.Delete, "Delete") { scope.launch { ctx.app.library.delete(id); nav.popBackStack() } }
        })
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (audio != null) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(pal.surface).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(if (playing) Ic.Pause else Ic.Play, if (playing) "Pause" else "Play", tint = pal.accentInk, bg = pal.accent, size = 44.dp) {
                    if (playing) { player.pause(); playing = false } else { player.start(); playing = true }
                }
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Slider(pos.toFloat(), { pos = it.toLong(); player.seekTo(it.toInt()) }, valueRange = 0f..maxOf(1f, player.duration.toFloat()),
                        colors = SliderDefaults.colors(thumbColor = pal.accent, activeTrackColor = pal.accent, inactiveTrackColor = pal.accentSoft))
                    Row { Text(clock(pos), style = Type.small, color = pal.muted, modifier = Modifier.weight(1f)); Text(clock(maxOf(duration, player.duration.toLong())), style = Type.small, color = pal.muted) }
                }
            }
            improve[id]?.let { p ->
                if (p in 0f..0.999f) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(pal.accentSoft).padding(14.dp)) {
                    Row { Text("Improving", fontWeight = FontWeight.SemiBold, color = pal.accent, modifier = Modifier.weight(1f)); Text("${(p * 100).toInt()}%", fontWeight = FontWeight.SemiBold, color = pal.accent) }
                    Spacer(Modifier.height(8.dp)); ProgressBar(p, height = 6.dp, track = pal.surface)
                }
            }
            if (!ctx.app.models.supported("whisper-small")) Unit
            else if (audio != null && improve[id] == null && !ctx.app.models.isInstalled("whisper-small") && !ctx.app.models.isInstalled("whisper-turbo"))
                Btn("Get Improve model", Modifier.fillMaxWidth(), kind = BtnKind.Soft) { nav.navigate("models") }
            else if (audio != null && improve[id] == null) Btn("Improve transcript", Modifier.fillMaxWidth(), kind = BtnKind.Soft) { Whisper.improveInBackground(ctx, id) }
        }
        LazyColumn(Modifier.weight(1f).padding(20.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(pal.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (segments.isEmpty()) item { Text("Nothing was transcribed.", color = pal.muted) }
            itemsIndexed(segments) { _, s ->
                Row {
                    Text(clock(s.startMs), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (s.marked) pal.warnInk else pal.muted,
                        modifier = Modifier.width(56.dp).clip(RoundedCornerShape(8.dp)).background(if (s.marked) pal.warnSoft else pal.surfaceAlt).tap { seek(s.startMs) }.padding(vertical = 5.dp), textAlign = TextAlign.Center)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.french, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = pal.ink)
                        s.english?.let { Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = pal.accent) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Copy", kind = BtnKind.Secondary, icon = Ic.Copy) { copy(ctx, segments.joinToString("\n") { it.french + (it.english?.let { e -> "\n$e" } ?: "") }) }
            Spacer(Modifier.weight(1f))
            Btn("Export", icon = Ic.Save) {
                scope.launch {
                    val f = File(dir, "transcript.docx")
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { exportTranscript(segments, item.title, f) }
                    exportFile(ctx, f, item.title)
                }
            }
        }
    }
}

/** Transcript → Word document: timestamps, French, English. */
fun exportTranscript(segs: List<Segment>, title: String, out: File) {
    val c = app.carnet.core.convert.CharStyle(size = 11f, font = "Calibri")
    val blocks = ArrayList<app.carnet.core.convert.BlockNode>()
    blocks.add(app.carnet.core.convert.Paragraph(listOf(app.carnet.core.convert.TextRun(title, c.copy(bold = true, size = 18f))), app.carnet.core.convert.ParaStyle(spaceAfter = 10f, heading = 1)))
    for (s in segs) {
        val inl = ArrayList<app.carnet.core.convert.Inline>()
        inl.add(app.carnet.core.convert.TextRun("[${clock(s.startMs)}] ", c.copy(color = 0xFF6B6F78.toInt(), size = 9f)))
        inl.add(app.carnet.core.convert.TextRun(s.french, c))
        s.english?.let { inl.add(app.carnet.core.convert.TextRun("\n$it", c.copy(color = 0xFF1F4FD1.toInt()))) }
        blocks.add(app.carnet.core.convert.Paragraph(inl, app.carnet.core.convert.ParaStyle(spaceAfter = 8f, shading = if (s.marked) 0xFFFFF4D6.toInt() else null)))
    }
    out.outputStream().use { app.carnet.core.convert.DocxWriter.write(app.carnet.core.convert.DocModel(listOf(app.carnet.core.convert.Section(blocks = blocks)), title), it) }
}
