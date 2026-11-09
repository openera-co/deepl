package app.carnet.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import app.carnet.app
import app.carnet.core.mt.GlossaryEntry
import app.carnet.data.AppSettings
import app.carnet.data.ItemType
import app.carnet.models.ModelStatus
import app.carnet.share.ShareClient
import app.carnet.share.ShareServer
import app.carnet.share.ShareService
import app.carnet.ui.BottomNav
import app.carnet.ui.Btn
import app.carnet.ui.BtnKind
import app.carnet.ui.Card
import app.carnet.ui.Chip
import app.carnet.ui.Divider
import app.carnet.ui.EmptyState
import app.carnet.ui.Ic
import app.carnet.ui.IconBtn
import app.carnet.ui.ListItemRow
import app.carnet.ui.Screen
import app.carnet.ui.SectionLabel
import app.carnet.ui.Segmented
import app.carnet.ui.SettingRow
import app.carnet.ui.ToggleRow
import app.carnet.ui.TopBar
import app.carnet.ui.Type
import app.carnet.ui.openItem
import app.carnet.ui.pal
import app.carnet.ui.tab
import kotlinx.coroutines.launch
import java.io.File

// ------------------------------------------------------------------------------------------ Library

@Composable
fun LibraryScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val items by ctx.app.library.items.collectAsState()
    var q by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(0) }
    val filters = listOf("All", "Documents", "Scans", "Recordings", "Text")
    val shown = items.filter {
        when (filter) { 1 -> it.type == ItemType.DOCUMENT || it.type == ItemType.CONVERSION; 2 -> it.type == ItemType.SCAN; 3 -> it.type == ItemType.RECORDING; 4 -> it.type == ItemType.TEXT; else -> true }
    }.filter { q.isBlank() || it.title.contains(q, true) || (it.meta["french"]?.contains(q, true) == true) || (it.meta["english"]?.contains(q, true) == true) }
    Screen {
        Text("Library", style = Type.title, color = pal.ink, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp)).background(pal.surface).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.Search, null, tint = pal.muted, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Text("Search", color = pal.faint, fontSize = 16.sp)
                    BasicTextField(q, { q = it }, singleLine = true, textStyle = Type.body.copy(color = pal.ink, fontSize = 16.sp), cursorBrush = SolidColor(pal.accent), modifier = Modifier.fillMaxWidth())
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { itemsIndexed(filters) { i, f -> Chip(f, i == filter) { filter = i } } }
        }
        Box(Modifier.weight(1f)) {
            if (shown.isEmpty()) EmptyState(Ic.Library, if (items.isEmpty()) "Your translations will appear here" else "Nothing found")
            else LazyColumn(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp)) {
                itemsIndexed(shown, key = { _, it -> it.id }) { i, it ->
                    Column(Modifier.animateItem()) {
                        if (i > 0) Divider()
                        ListItemRow(it.badge, it.title, subtitle(it), onClick = { nav.openItem(it) })
                    }
                }
            }
        }
        BottomNav("library") { nav.tab(it) }
    }
}

// ------------------------------------------------------------------------------------------ Settings

@Composable
fun SettingsScreen(nav: NavHostController, s: AppSettings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun set(f: (AppSettings) -> AppSettings) = scope.launch { ctx.app.settings.update(f) }
    var confirmClear by remember { mutableStateOf(false) }
    Screen {
        TopBar("Settings", onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            SectionLabel("Translation")
            Card {
                SettingRow("Glossary", "${s.glossary.size}", onClick = { nav.navigate("glossary") })
                Divider()
                SettingRow("Spelling", chevron = false) { Segmented(listOf("UK", "US"), if (s.spelling == "US") 1 else 0, Modifier.width(120.dp)) { i -> set { it.copy(spelling = if (i == 1) "US" else "UK") } } }
                Divider()
                SettingRow("Documents", chevron = false) { Segmented(listOf("Fast", "Careful"), if (s.quality == "careful") 1 else 0, Modifier.width(170.dp)) { i -> set { it.copy(quality = if (i == 1) "careful" else "fast") } } }
            }
            SectionLabel("Files")
            Card {
                SettingRow("Save as", chevron = false) { Segmented(listOf("Same", "PDF"), if (s.saveAs == "pdf") 1 else 0, Modifier.width(140.dp)) { i -> set { it.copy(saveAs = if (i == 1) "pdf" else "same") } } }
                Divider()
                ToggleRow("Bilingual copy", s.bilingual) { v -> set { it.copy(bilingual = v) } }
            }
            SectionLabel("Record")
            Card {
                ToggleRow("Spoken punctuation", s.spokenPunctuation) { v -> set { it.copy(spokenPunctuation = v) } }
                Divider()
                ToggleRow("Improve after stop", s.improveAfterStop) { v -> set { it.copy(improveAfterStop = v) } }
            }
            SectionLabel("Models")
            Card {
                SettingRow("Models", formatSize(ctx.app.models.installedBytes() + (105L shl 20)), onClick = { nav.navigate("models") })
                Divider()
                SettingRow("Share with another phone", onClick = { nav.navigate("share") })
                Divider()
                SettingRow("Share the app", onClick = {
                    val apk = File(ctx.applicationInfo.sourceDir)
                    val copy = File(ctx.cacheDir, "Carnet.apk"); apk.copyTo(copy, true)
                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", copy)
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Carnet.apk"))
                })
            }
            SectionLabel("Appearance")
            Card {
                SettingRow("Theme", chevron = false) {
                    Segmented(listOf("System", "Light", "Dark"), when (s.theme) { "light" -> 1; "dark" -> 2; else -> 0 }, Modifier.width(210.dp)) { i -> set { it.copy(theme = listOf("system", "light", "dark")[i]) } }
                }
            }
            SectionLabel("Storage")
            Card {
                SettingRow("Library", formatSize(ctx.app.library.sizeBytes()), chevron = false)
                Divider()
                AnimatedContent(confirmClear, label = "clear") { c ->
                    if (!c) SettingRow("Clear library", labelColor = pal.danger, chevron = false, onClick = { confirmClear = true })
                    else Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Btn("Cancel", Modifier.weight(1f), kind = BtnKind.Secondary) { confirmClear = false }
                        Btn("Delete all", Modifier.weight(1f), kind = BtnKind.Danger) { scope.launch { ctx.app.library.clear(); confirmClear = false } }
                    }
                }
            }
            Text("Carnet ${ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName}", style = Type.small, color = pal.muted, modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp).wrapCenter())
        }
    }
}

private fun Modifier.wrapCenter() = this

// ------------------------------------------------------------------------------------------ Models

@Composable
fun ModelsScreen(nav: NavHostController, s: AppSettings) {
    val ctx = LocalContext.current
    val status by ctx.app.models.status.collectAsState()
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            msg = "Checking files…"
            val got = ctx.app.models.importFiles(uris)
            msg = if (got.isEmpty()) "Those files aren't Carnet models" else "Imported: ${got.joinToString()}"
        }
    }
    Screen {
        TopBar("Models", onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            val used = ctx.app.models.installedBytes() + (109L shl 20)
            val free = ctx.filesDir.usableSpace
            Card(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                Row { Text("Storage", style = Type.bodyStrong, color = pal.ink, modifier = Modifier.weight(1f)); Text("${formatSize(used)} used · ${formatSize(free)} free", style = Type.small, color = pal.muted) }
                Spacer(Modifier.height(10.dp))
                app.carnet.ui.ProgressBar(used.toFloat() / (used + free).coerceAtLeast(1), height = 8.dp, track = pal.surfaceAlt)
            }
            SectionLabel("Built in")
            Card {
                ctx.app.models.catalog.filter { it.builtIn }.forEachIndexed { i, m ->
                    if (i > 0) Divider()
                    ModelRow(m.title, formatSize(m.size), ModelStatus.Installed, onGet = {}, onPause = {})
                }
            }
            SectionLabel("Downloads")
            Card {
                val optional = ctx.app.models.catalog.filter { !it.builtIn && ctx.app.models.supported(it.id) }
                if (optional.isEmpty()) SettingRow("No optional models for this phone", chevron = false)
                optional.forEachIndexed { i, m ->
                    if (i > 0) Divider()
                    ModelRow(m.title, formatSize(m.size), status[m.id] ?: ModelStatus.NotInstalled, onGet = { ctx.app.models.download(m.id) }, onPause = { ctx.app.models.pause(m.id) }, onDelete = { ctx.app.models.delete(m.id) })
                }
            }
            Spacer(Modifier.height(12.dp))
            Card { ToggleRow("Wi-Fi only", s.wifiOnly) { v -> scope.launch { ctx.app.settings.update { it.copy(wifiOnly = v) } } } }
            msg?.let { Text(it, style = Type.small, color = pal.muted, modifier = Modifier.padding(top = 12.dp)) }
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Get from another phone", Modifier.fillMaxWidth(), kind = BtnKind.Soft, icon = Ic.Qr) { nav.navigate("share") }
            Btn("Import from file", Modifier.fillMaxWidth(), kind = BtnKind.Secondary, icon = Ic.Download) { import.launch(arrayOf("*/*")) }
        }
    }
}

// ------------------------------------------------------------------------------------------ Glossary

@Composable
fun GlossaryScreen(nav: NavHostController, s: AppSettings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var fr by remember { mutableStateOf("") }
    var en by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    fun save(list: List<GlossaryEntry>) = scope.launch { ctx.app.settings.update { it.copy(glossary = list) } }
    Screen(Modifier.imePadding()) {
        TopBar("Glossary", onBack = { nav.popBackStack() })
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp)).background(pal.surface).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.Search, null, tint = pal.muted, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Text("Search", color = pal.faint, fontSize = 16.sp)
                    BasicTextField(q, { q = it }, singleLine = true, textStyle = Type.body.copy(color = pal.ink, fontSize = 16.sp), cursorBrush = SolidColor(pal.accent), modifier = Modifier.fillMaxWidth())
                }
            }
            Row(Modifier.padding(horizontal = 16.dp)) { Text("FRENCH", style = Type.section, color = pal.muted, modifier = Modifier.weight(1f)); Text("ENGLISH", style = Type.section, color = pal.muted, modifier = Modifier.weight(1f)); Spacer(Modifier.width(40.dp)) }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 8.dp).clip(RoundedCornerShape(18.dp)).background(pal.surface)) {
            val list = s.glossary.filter { q.isBlank() || it.source.contains(q, true) || it.target.contains(q, true) }
            itemsIndexed(list, key = { _, e -> e.source + "|" + e.target }) { i, e ->
                Column(Modifier.animateItem()) {
                    if (i > 0) Divider()
                    Row(Modifier.fillMaxWidth().height(54.dp).padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(e.source, style = Type.bodyStrong, color = pal.ink, modifier = Modifier.weight(1f))
                        Text(e.target, style = Type.body, color = pal.muted, modifier = Modifier.weight(1f))
                        IconBtn(Ic.Delete, "Remove ${e.source}", tint = pal.faint, size = 40.dp) { save(s.glossary - e) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(pal.bg).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((v, set, hint) in listOf(Triple(fr, { x: String -> fr = x }, "French"), Triple(en, { x: String -> en = x }, "English"))) {
                Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)).background(pal.surface).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                    if (v.isEmpty()) Text(hint, color = pal.faint, style = Type.body)
                    BasicTextField(v, set, singleLine = true, textStyle = Type.body.copy(color = pal.ink), cursorBrush = SolidColor(pal.accent), modifier = Modifier.fillMaxWidth())
                }
            }
            IconBtn(Ic.Add, "Add term", tint = pal.accentInk, bg = pal.accent, size = 48.dp, enabled = fr.isNotBlank() && en.isNotBlank()) {
                save(s.glossary.filter { !it.source.equals(fr.trim(), true) } + GlossaryEntry(fr.trim(), en.trim())); fr = ""; en = ""
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ Share models between phones

@Composable
fun ShareModelsScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val server by ShareServer.state.collectAsState()
    val status by ctx.app.models.status.collectAsState()
    var mode by remember { mutableStateOf(0) }
    var link by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf<List<ShareClient.RemoteModel>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun connect(l: String) {
        val r = ShareClient.parse(l) ?: run { error = "That code isn't a Carnet share code"; return }
        scope.launch { runCatching { remote = ShareClient.manifest(r); error = null }.onFailure { error = it.message ?: "Couldn't reach the other phone" } }
    }
    Screen {
        TopBar("Share models", onBack = { nav.popBackStack() })
        Segmented(listOf("Send", "Receive"), mode, Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp)) { mode = it; if (it == 0) remote = null }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (mode == 0) {
                val installed = ctx.app.models.catalog.filter { !it.builtIn && status[it.id] is ModelStatus.Installed }
                if (installed.isEmpty()) EmptyState(Ic.Model, "No downloaded models to share")
                else if (!server.running) {
                    Card { installed.forEachIndexed { i, m -> if (i > 0) Divider(); SettingRow(m.title, formatSize(m.size), chevron = false) } }
                    Btn("Start sharing", Modifier.fillMaxWidth(), icon = Ic.Wifi) { ShareService.start(ctx) }
                } else {
                    val l = ShareServer.link()
                    Card(padding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
                        if (l != null) {
                            val qr = remember(l) { ShareClient.qr(l) }
                            Image(qr.asImageBitmap(), "Share code", modifier = Modifier.fillMaxWidth().height(260.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(l.removePrefix("carnet://share?"), style = Type.small, color = pal.muted)
                        } else Text("Connect to Wi-Fi or turn on your hotspot", style = Type.body, color = pal.ink)
                        Spacer(Modifier.height(10.dp))
                        Text("Sent ${formatSize(server.sent)}", style = Type.small, color = pal.muted)
                    }
                    Btn("Stop sharing", Modifier.fillMaxWidth(), kind = BtnKind.Secondary) { ShareService.stop(ctx) }
                }
            } else {
                if (remote == null) {
                    if (scanning) QrScanner { code -> scanning = false; link = code; connect(code) }
                    else {
                        Btn("Scan code", Modifier.fillMaxWidth(), icon = Ic.Qr) { scanning = true }
                        Card {
                            Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    if (link.isEmpty()) Text("or type the code", color = pal.faint, style = Type.body)
                                    BasicTextField(link, { link = it }, singleLine = true, textStyle = Type.body.copy(color = pal.ink), cursorBrush = SolidColor(pal.accent), modifier = Modifier.fillMaxWidth())
                                }
                                Btn("Connect", kind = BtnKind.Soft, height = 36.dp, enabled = link.isNotBlank()) { connect(if (link.startsWith("carnet://")) link else "carnet://share?$link") }
                            }
                        }
                    }
                } else {
                    val r = ShareClient.parse(link.let { if (it.startsWith("carnet://")) it else "carnet://share?$it" })!!
                    Card {
                        if (remote!!.isEmpty()) SettingRow("The other phone has no models to share", chevron = false)
                        remote!!.forEachIndexed { i, m ->
                            if (i > 0) Divider()
                            ModelRow(m.title, formatSize(m.size), status[m.id] ?: ModelStatus.NotInstalled, onGet = { ctx.app.models.download(m.id, r.base, r.token) }, onPause = { ctx.app.models.pause(m.id) })
                        }
                    }
                }
            }
            error?.let { Text(it, color = pal.danger, style = Type.small) }
        }
    }
}
