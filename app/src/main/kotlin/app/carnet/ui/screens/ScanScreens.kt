package app.carnet.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import app.carnet.app
import app.carnet.convert.Converters
import app.carnet.data.AppSettings
import app.carnet.data.ItemType
import app.carnet.data.LibraryItem
import app.carnet.nativeai.Gemma
import app.carnet.ui.Btn
import app.carnet.ui.BtnKind
import app.carnet.ui.Card
import app.carnet.ui.Ic
import app.carnet.ui.IconBtn
import app.carnet.ui.Screen
import app.carnet.ui.Segmented
import app.carnet.ui.TopBar
import app.carnet.ui.Type
import app.carnet.ui.pal
import app.carnet.ui.tap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ScanScreen(nav: NavHostController, settings: AppSettings) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var mode by remember { mutableStateOf(0) } // 0 printed, 1 handwriting, 2 pages
    var flash by remember { mutableStateOf(false) }
    val pages = remember { mutableStateListOf<File>() }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var shutter by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun process(files: List<File>) {
        val handwriting = mode == 1
        busy = "Reading"
        val id = ctx.app.jobs.run(if (files.size > 1) "Scanned pages" else "Scan") { report, cancel ->
            scanFiles(ctx, files, handwriting) { s, d, t -> busy = s; report(s, d, t) }
        }
        scope.launch {
            ctx.app.jobs.state(id)?.collect { st ->
                if (st.finished) {
                    busy = null
                    if (st.resultId != null) nav.navigate("scanResult/${st.resultId}") { popUpTo("scan") { inclusive = true } }
                    else android.widget.Toast.makeText(ctx, st.error ?: "Failed", android.widget.Toast.LENGTH_LONG).show()
                    return@collect
                }
            }
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val files = withContext(Dispatchers.IO) { uris.mapIndexed { i, u -> File(ctx.cacheDir, "pick-$i-${System.nanoTime()}.jpg").also { f -> ctx.contentResolver.openInputStream(u)!!.use { it.copyTo(f.outputStream()) } } } }
            process(files)
        }
    }

    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color(0xFF0E0F12))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Ic.Close, "Close", tint = Color.White) { nav.popBackStack() }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("French → English", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 6.dp))
                }
                IconBtn(Ic.Flash, "Flash", tint = if (flash) Color(0xFFFFD54F) else Color.White) { flash = !flash; capture?.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF }
            }
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
                if (granted) {
                    AndroidView(factory = { c ->
                        PreviewView(c).also { pv ->
                            pv.scaleType = PreviewView.ScaleType.FILL_CENTER
                            val providerF = ProcessCameraProvider.getInstance(c)
                            providerF.addListener({
                                val provider = providerF.get()
                                val preview = Preview.Builder().build().also { it.surfaceProvider = pv.surfaceProvider }
                                val ic = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                                capture = ic
                                provider.unbindAll()
                                runCatching { provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, ic) }
                            }, ContextCompat.getMainExecutor(c))
                        }
                    }, modifier = Modifier.fillMaxSize())
                    // Corner guides.
                    Box(Modifier.fillMaxSize().padding(28.dp).border(2.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(14.dp)))
                    val flashA by animateFloatAsState(if (shutter) 0.85f else 0f, tween(if (shutter) 40 else 260), label = "shutter")
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = flashA }.background(Color.White))
                } else {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("Camera access needed", color = Color.White, fontSize = 16.sp)
                        Spacer(Modifier.height(12.dp))
                        Btn("Allow", kind = BtnKind.Soft) { ask.launch(Manifest.permission.CAMERA) }
                    }
                }
                androidx.compose.animation.AnimatedVisibility(visible = busy != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
                    Column(Modifier.clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Dots(Color.White); Spacer(Modifier.height(10.dp))
                        Text(busy ?: "", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (pages.isNotEmpty()) Text("${pages.size} page${if (pages.size > 1) "s" else ""}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 6.dp))
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Printed", "Handwriting", "Pages").forEachIndexed { i, l ->
                        Text(l, color = if (i == mode) Color.White else Color(0xFFB9BCC3), fontSize = 13.sp, fontWeight = if (i == mode) FontWeight.SemiBold else FontWeight.Medium,
                            modifier = Modifier.clip(CircleShape).background(if (i == mode) Color.White.copy(alpha = 0.16f) else Color.Transparent).tap { mode = i }.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
                if (mode == 1 && !ctx.app.models.isInstalled("handwriting")) {
                    Text("Get the handwriting model for best results", color = Color(0xFFB9BCC3), fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp).tap { nav.navigate("models") })
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    IconBtn(Ic.Gallery, "Choose from gallery", tint = Color.White, size = 52.dp, bg = Color.White.copy(alpha = 0.14f)) { gallery.launch("image/*") }
                    Box(Modifier.size(76.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).tap(haptic = true, scaleTo = 0.9f) {
                        val ic = capture ?: return@tap
                        if (busy != null) return@tap
                        val f = File(ctx.cacheDir, "cap-${System.nanoTime()}.jpg")
                        shutter = true
                        ic.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                                shutter = false
                                if (mode == 2) pages.add(f) else process(listOf(f))
                            }
                            override fun onError(e: ImageCaptureException) { shutter = false; android.widget.Toast.makeText(ctx, e.message, android.widget.Toast.LENGTH_SHORT).show() }
                        })
                    }, contentAlignment = Alignment.Center) { Box(Modifier.size(60.dp).clip(CircleShape).background(Color.White)) }
                    if (mode == 2 && pages.isNotEmpty()) IconBtn(Ic.Check, "Done", tint = Color.Black, size = 52.dp, bg = Color.White) { process(pages.toList()) }
                    else Spacer(Modifier.size(52.dp))
                }
            }
        }
    }
}

/** OCR + translate + draw each photo; returns the library item (one page or a PDF of pages). */
suspend fun scanFiles(ctx: Context, files: List<File>, handwriting: Boolean, report: (String, Int, Int) -> Unit): LibraryItem {
    val lib = ctx.app.library
    val id = lib.newId()
    val dir = lib.dir(id)
    val fr = StringBuilder(); val en = StringBuilder()
    val results = ArrayList<Bitmap>()
    files.forEachIndexed { i, f ->
        report(if (files.size > 1) "Page ${i + 1} of ${files.size}" else "Reading", i, files.size)
        val bmp = ctx.app.scanner.load(f)
        val page = if (handwriting && ctx.app.models.isInstalled("handwriting")) Gemma.get(ctx).scanPage(bmp, ctx) else ctx.app.scanner.scan(bmp) { st -> report(st.replaceFirstChar { it.uppercase() }, i, files.size) }
        withContext(Dispatchers.IO) {
            File(dir, "page-${i + 1}.jpg").outputStream().use { page.original.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            File(dir, "page-${i + 1}.en.jpg").outputStream().use { page.translated.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        }
        if (i > 0) { fr.append("\n\n"); en.append("\n\n") }
        fr.append(page.frenchText); en.append(page.englishText)
        results.add(page.translated)
    }
    File(dir, "french.txt").writeText(fr.toString()); File(dir, "english.txt").writeText(en.toString())
    if (files.size > 1) Converters.imagesToPdf(results, File(dir, "result.pdf"))
    val title = en.lineSequence().firstOrNull { it.isNotBlank() }?.take(48) ?: "Scan"
    return LibraryItem(id, ItemType.SCAN, title, System.currentTimeMillis(), "page-1.jpg", if (files.size > 1) "result.pdf" else "page-1.en.jpg",
        mapOf("pages" to files.size.toString(), "handwriting" to handwriting.toString()))
}

@Composable
fun ScanResultScreen(nav: NavHostController, id: String) {
    val ctx = LocalContext.current
    val item = ctx.app.library.get(id) ?: run { nav.popBackStack(); return }
    val dir = ctx.app.library.dir(id)
    val pages = (item.meta["pages"]?.toIntOrNull() ?: 1)
    var page by remember { mutableStateOf(0) }
    var tab by remember { mutableStateOf(1) }
    var fr by remember { mutableStateOf(File(dir, "french.txt").takeIf { it.exists() }?.readText() ?: "") }
    var en by remember { mutableStateOf(File(dir, "english.txt").takeIf { it.exists() }?.readText() ?: "") }
    var improving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val orig = remember(page) { BitmapFactory.decodeFile(File(dir, "page-${page + 1}.jpg").path) }
    val tr = remember(page, improving) { BitmapFactory.decodeFile(File(dir, "page-${page + 1}.en.jpg").path) }
    LaunchedEffect(fr) {
        if (tab == 2 && fr.isNotBlank()) { kotlinx.coroutines.delay(400); en = ctx.app.engine.translate(fr); File(dir, "english.txt").writeText(en); File(dir, "french.txt").writeText(fr) }
    }
    Screen {
        TopBar(item.title, onBack = { nav.popBackStack() }, actions = {
            IconBtn(Ic.Rotate, "Rotate") {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        for (n in listOf("page-${page + 1}.jpg", "page-${page + 1}.en.jpg")) {
                            val f = File(dir, n); val b = BitmapFactory.decodeFile(f.path) ?: continue
                            f.outputStream().use { ctx.app.scanner.rotate(b, 90).compress(Bitmap.CompressFormat.JPEG, 92, it) }
                        }
                    }
                    improving = !improving; improving = !improving
                }
            }
        })
        Segmented(listOf("Original", "English", "Text"), tab, Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp)) { tab = it }
        Box(Modifier.weight(1f).fillMaxWidth().background(pal.surfaceAlt)) {
            AnimatedContent(tab, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "tab") { t ->
                when (t) {
                    0, 1 -> ZoomImage(if (t == 0) orig else tr)
                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Card(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                            Text("FRENCH", style = Type.section, color = pal.muted); Spacer(Modifier.height(8.dp))
                            BasicTextField(fr, { fr = it }, textStyle = Type.body.copy(color = pal.ink), cursorBrush = SolidColor(pal.accent), modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
                        }
                        Card(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                            Text("ENGLISH", style = Type.section, color = pal.muted); Spacer(Modifier.height(8.dp))
                            SelectionContainer { Text(en, style = Type.body, color = pal.ink) }
                        }
                    }
                }
            }
            if (pages > 1) Row(Modifier.align(Alignment.BottomCenter).padding(12.dp).clip(CircleShape).background(pal.surface).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Ic.Back, "Previous page", size = 36.dp, enabled = page > 0) { page-- }
                Text("${page + 1} / $pages", fontWeight = FontWeight.SemiBold, color = pal.ink, modifier = Modifier.padding(horizontal = 6.dp))
                IconBtn(Ic.Chevron, "Next page", size = 36.dp, enabled = page < pages - 1) { page++ }
            }
            if (improving) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                Column(Modifier.clip(RoundedCornerShape(18.dp)).background(pal.surface).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Dots(pal.accent); Spacer(Modifier.height(8.dp)); Text("Improving…", color = pal.ink, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(pal.surface).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Btn("Copy", kind = BtnKind.Secondary, icon = Ic.Copy) { copy(ctx, if (tab == 0) fr else en); android.widget.Toast.makeText(ctx, "Copied", android.widget.Toast.LENGTH_SHORT).show() }
            Btn("Improve", kind = BtnKind.Secondary, enabled = !improving) {
                if (!ctx.app.models.isInstalled("handwriting")) { nav.navigate("models"); return@Btn }
                improving = true
                scope.launch {
                    runCatching {
                        val bmp = BitmapFactory.decodeFile(File(dir, "page-${page + 1}.jpg").path)
                        val res = Gemma.get(ctx).scanPage(bmp, ctx)
                        withContext(Dispatchers.IO) { File(dir, "page-${page + 1}.en.jpg").outputStream().use { res.translated.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
                        en = res.englishText; fr = res.frenchText
                        File(dir, "english.txt").writeText(en)
                    }.onFailure { android.widget.Toast.makeText(ctx, it.message, android.widget.Toast.LENGTH_LONG).show() }
                    improving = false
                }
            }
            Spacer(Modifier.weight(1f))
            Btn("Save", icon = Ic.Save) { scope.launch { exportFile(ctx, File(dir, item.resultName ?: "page-1.en.jpg"), item.title) } }
        }
    }
}

@Composable
fun ZoomImage(bmp: Bitmap?) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val st = rememberTransformableState { z, pan, _ -> scale = (scale * z).coerceIn(1f, 5f); offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan }
    Box(Modifier.fillMaxSize().transformable(st).padding(16.dp), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
    }
}

/** Saves a result to Downloads/Carnet (Android 10+) and offers the share sheet. */
suspend fun exportFile(ctx: Context, f: File, title: String) {
    val name = title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(60).ifEmpty { "Carnet" } + "." + f.extension
    val saved = withContext(Dispatchers.IO) {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime(f.extension))
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/Carnet")
                }
                val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                ctx.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
                true
            } else false
        }.getOrDefault(false)
    }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    val send = Intent(Intent.ACTION_SEND).setType(mime(f.extension)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (saved) android.widget.Toast.makeText(ctx, "Saved to Downloads/Carnet", android.widget.Toast.LENGTH_SHORT).show()
    ctx.startActivity(Intent.createChooser(send, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun mime(ext: String) = when (ext.lowercase()) {
    "pdf" -> "application/pdf"; "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "odt" -> "application/vnd.oasis.opendocument.text"; "txt", "md", "csv", "srt" -> "text/plain"
    "wav" -> "audio/wav"; "zip" -> "application/zip"
    else -> "application/octet-stream"
}
