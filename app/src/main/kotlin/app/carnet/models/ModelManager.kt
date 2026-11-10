package app.carnet.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import app.carnet.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ModelFile(val name: String, val url: String, val size: Long, val sha256: String)

data class ModelSpec(
    val id: String,
    val title: String,
    val purpose: String,
    val files: List<ModelFile>,
    /** "fr-en" / "en-fr" for translation models, null for others. */
    val direction: String? = null,
    /** "best" or "fast" for translation models. */
    val quality: String? = null,
    /** Part of the one-button "get everything you need" download. */
    val recommended: Boolean = false,
) {
    val size: Long get() = files.sumOf { it.size }
    val isTranslation get() = direction != null

    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("purpose", purpose)
        .put("direction", direction ?: JSONObject.NULL).put("quality", quality ?: JSONObject.NULL).put("recommended", recommended)
        .put("files", JSONArray(files.map { JSONObject().put("name", it.name).put("url", it.url).put("size", it.size).put("sha256", it.sha256) }))

    companion object {
        fun fromJson(o: JSONObject): ModelSpec {
            val fs = o.getJSONArray("files")
            return ModelSpec(
                o.getString("id"), o.getString("title"), o.optString("purpose"),
                (0 until fs.length()).map { fs.getJSONObject(it).let { f -> ModelFile(f.getString("name"), f.optString("url"), f.getLong("size"), f.getString("sha256")) } },
                o.optString("direction").takeIf { it.isNotEmpty() && it != "null" },
                o.optString("quality").takeIf { it.isNotEmpty() && it != "null" },
                o.optBoolean("recommended"),
            )
        }
    }
}

sealed class ModelStatus {
    object NotInstalled : ModelStatus()
    /** [speed] in bytes per second (0 while connecting); [waiting] explains a pause the user didn't ask for. */
    data class Downloading(val done: Long, val total: Long, val paused: Boolean = false, val speed: Long = 0, val waiting: String? = null) : ModelStatus()
    object Verifying : ModelStatus()
    object Installed : ModelStatus()
    data class Failed(val reason: String) : ModelStatus()
}

/**
 * On-device models: one-button download (resumable, checksummed, retried, kept alive in the
 * background by [DownloadService]), import from a file, and sharing with other phones (ShareServer).
 * The translation models' catalog (models.json) comes from the app's GitHub release "models-v1";
 * a copy is bundled at build time so the app knows the files even offline (e.g. to receive them
 * from another phone).
 */
class ModelManager(private val context: Context) {
    private val root = File(context.filesDir, "models").apply { mkdirs() }
    private val cachedCatalog = File(root, "catalog.json")

    /** Models that are not translation models (fixed URLs). */
    private val extras = listOf(
        ModelSpec("handwriting", "Handwriting · advanced", "", listOf(
            ModelFile("model.gguf", "https://huggingface.co/mradermacher/translategemma-4b-it-GGUF/resolve/main/translategemma-4b-it.Q4_K_M.gguf", 2489909760, "81200d03e843d2ec1ece6eeafe7d13cb6e5211e1fcd336ade55790b683a08330"),
            ModelFile("vision.gguf", "https://huggingface.co/mradermacher/translategemma-4b-it-GGUF/resolve/main/translategemma-4b-it.mmproj-Q8_0.gguf", 591377600, "482f68be8823dfdfb3561c22cdd1c0f617d0d5e6efe9c323cc6428a4331dba01"),
        )),
        ModelSpec("whisper-base", "Offline speech", "", listOf(
            // Whisper base fine-tuned on French (qanastek, Apache-2.0): 29.9% vs 35.4% WER on French FLEURS (tools/speech).
            ModelFile("model.bin", "https://github.com/openera-co/deepl/releases/download/models-v2/whisper-base-fr__model.bin", 59707625, "ec2e04c1004bb608ece301fb1a853a81a6b04ec911681a48ed4f8dcefdf539bf"),
        )),
        ModelSpec("whisper-small", "Offline speech · better", "", listOf(
            ModelFile("model.bin", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin", 190085487, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"),
        )),
        ModelSpec("whisper-turbo", "Offline speech · best", "", listOf(
            ModelFile("model.bin", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin", 574041195, "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"),
        )),
    )

    private val _catalog = MutableStateFlow(loadCatalog())
    val catalogFlow: StateFlow<List<ModelSpec>> = _catalog
    val catalog: List<ModelSpec> get() = _catalog.value

    private val _status = MutableStateFlow(catalog.associate { it.id to initialStatus(it) })
    val status: StateFlow<Map<String, ModelStatus>> = _status
    private val jobs = HashMap<String, Job>()
    /** Downloads run one at a time (full bandwidth for the first model, so it becomes usable sooner). */
    private val queue = Mutex()

    private fun loadCatalog(): List<ModelSpec> {
        val text = runCatching { cachedCatalog.readText() }.getOrNull()
            ?: runCatching { context.assets.open("models.json").use { String(it.readBytes()) } }.getOrNull()
        val translation = text?.let { parseCatalog(it) } ?: emptyList()
        // Models adopted from another phone that aren't in the catalog (e.g. a newer version).
        val adopted = root.listFiles()?.mapNotNull { d -> File(d, "spec.json").takeIf { it.exists() }?.let { runCatching { ModelSpec.fromJson(JSONObject(it.readText())) }.getOrNull() } }
            ?.filter { a -> translation.none { it.id == a.id } && extras.none { it.id == a.id } } ?: emptyList()
        return translation + adopted + extras
    }

    private fun parseCatalog(text: String): List<ModelSpec>? = runCatching {
        val a = JSONObject(text).getJSONArray("models")
        (0 until a.length()).map { ModelSpec.fromJson(a.getJSONObject(it)) }
    }.getOrNull()

    /** Fetches the latest catalog (new or improved models). Keeps the old one when offline. */
    suspend fun refreshCatalog() = withContext(Dispatchers.IO) {
        runCatching {
            val text = httpText(CATALOG_URL)
            if (parseCatalog(text).isNullOrEmpty()) return@runCatching
            cachedCatalog.writeText(text)
            _catalog.value = loadCatalog()
            _status.value = catalog.associate { it.id to (_status.value[it.id]?.takeIf { s -> s !is ModelStatus.NotInstalled } ?: initialStatus(it)) }
        }
        refreshUpdates()
        // Translation model updates (e.g. the switch to the faster engine) install themselves on Wi-Fi.
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        if (cm != null && !cm.isActiveNetworkMetered)
            _updates.value.keys.filter { specOrNull(it)?.direction != null }.forEach { update(it) }
    }

    fun spec(id: String) = catalog.first { it.id == id }
    fun specOrNull(id: String) = catalog.firstOrNull { it.id == id }

    /** Optional models need their native engine in this build and a compatible CPU. */
    fun supported(id: String): Boolean = when {
        id == "handwriting" -> app.carnet.nativeai.NativeLib.llamaLoaded
        id.startsWith("whisper") -> app.carnet.nativeai.NativeLib.whisperLoaded
        else -> true
    }
    fun dir(id: String) = File(root, id)
    fun file(id: String, name: String) = File(dir(id), name)
    fun isInstalled(id: String) = _status.value[id] is ModelStatus.Installed

    /** The installed translation model for a direction ("fr-en"/"en-fr"), best quality first unless [preferFast]. */
    fun translationModel(direction: String, preferFast: Boolean = false): ModelSpec? {
        // A model being updated stays usable: its old files are replaced only once the new ones are in.
        val installed = catalog.filter { it.direction == direction && (isInstalled(it.id) || (jobs[it.id]?.isActive == true && installedSpec(it.id) != null)) }
        return installed.sortedBy { if ((it.quality == "fast") == preferFast) 0 else 1 }.firstOrNull()
    }

    /** The models the one button downloads for a quality ("best"/"fast"): both directions, plus the handwriting reader. */
    fun pack(quality: String): List<ModelSpec> = catalog.filter { (it.direction != null && it.quality == quality) || (it.direction == null && it.recommended) }.filter { supported(it.id) }

    fun recommendedMissing(quality: String = "best"): List<ModelSpec> = pack(quality).filter { !isInstalled(it.id) }

    /** What is on disk (may be an older version than the catalog's). */
    fun installedSpec(id: String): ModelSpec? = File(dir(id), "spec.json").takeIf { it.exists() && File(dir(id), ".ok").exists() }
        ?.let { runCatching { ModelSpec.fromJson(JSONObject(it.readText())) }.getOrNull() }

    private fun installedOk(s: ModelSpec): Boolean {
        val have = installedSpec(s.id) ?: return false
        return have.files.isNotEmpty() && have.files.all { File(dir(s.id), it.name).let { f -> f.exists() && f.length() == it.size } }
    }

    private val _updates = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Installed models with a newer version: id → bytes to download (only the files that changed). */
    val updates: StateFlow<Map<String, Long>> = _updates
    private val _generation = MutableStateFlow(0)
    /** Bumped whenever a model's files change on disk (engines reload). */
    val generation: StateFlow<Int> = _generation

    private fun changedFiles(spec: ModelSpec): List<ModelFile> {
        val have = installedSpec(spec.id)?.files?.associate { it.name to it.sha256 } ?: return spec.files
        return spec.files.filter { have[it.name] != it.sha256 }
    }

    private fun refreshUpdates() {
        _updates.value = catalog.filter { isInstalled(it.id) }.mapNotNull { s -> changedFiles(s).takeIf { it.isNotEmpty() }?.let { s.id to it.sumOf { f -> f.size } } }.toMap()
    }

    /** Updates an installed model in place: downloads only the changed files, then swaps them in. */
    fun update(id: String) {
        if (jobs[id]?.isActive == true) return
        val spec = specOrNull(id) ?: return
        val changed = changedFiles(spec)
        if (changed.isEmpty()) return
        val total = changed.sumOf { it.size }
        set(id, ModelStatus.Downloading(0, total, waiting = "Waiting…"))
        DownloadService.start(context)
        jobs[id] = context.app.scope.launch(Dispatchers.IO) {
            try {
                queue.withLock {
                    val d = dir(id); var done = 0L; val meter = SpeedMeter()
                    for (f in changed) {
                        val part = File(d, f.name + ".new")
                        done += part.length()
                        awaitAllowedNetwork(id, done, total)
                        val job = kotlin.coroutines.coroutineContext[Job]
                        fetch(f.url, part, null, { job?.isActive != false }) { n -> done += n; set(id, ModelStatus.Downloading(done, total, speed = meter.add(n))) }
                        if (sha256(part) != f.sha256) { part.delete(); throw IllegalStateException("damaged in transfer") }
                    }
                    set(id, ModelStatus.Verifying)
                    for (f in changed) File(d, f.name + ".new").renameTo(File(d, f.name))
                    spec.files.map { it.name }.toSet().let { keep -> d.listFiles()?.filter { it.name !in keep && it.name != ".ok" && it.name != "spec.json" }?.forEach { it.delete() } }
                    File(d, "spec.json").writeText(spec.toJson().toString())
                    set(id, ModelStatus.Installed)
                    _generation.value++
                    refreshUpdates()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                set(id, ModelStatus.Installed)
            } catch (e: Throwable) {
                app.carnet.diag.Diag.report(e, "model.update", mapOf("model" to id))
                set(id, ModelStatus.Installed)
            }
        }
    }

    private fun initialStatus(s: ModelSpec): ModelStatus = when {
        installedOk(s) -> ModelStatus.Installed
        installedSpec(s.id) == null && s.files.any { File(dir(s.id), it.name + ".part").exists() } ->
            ModelStatus.Downloading(s.files.sumOf { f -> File(dir(s.id), f.name).takeIf { it.exists() && it.length() == f.size }?.length() ?: File(dir(s.id), f.name + ".part").length() }, s.size, paused = true)
        else -> ModelStatus.NotInstalled
    }

    private fun set(id: String, st: ModelStatus) { _status.value = _status.value + (id to st) }

    fun installedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    val anyDownloading: Boolean get() = _status.value.values.any { it is ModelStatus.Downloading && !it.paused || it is ModelStatus.Verifying }

    /** One button: downloads every recommended model (translation both ways). */
    fun downloadRecommended(quality: String = "best") { recommendedMissing(quality).forEach { download(it.id) } }

    /**
     * Downloads (or resumes) all files of a model, from its origin or from another phone ([baseUrl]).
     * Network drops are retried with back-off; the download continues in the background.
     */
    fun download(id: String, baseUrl: String? = null, token: String? = null) {
        if (jobs[id]?.isActive == true) return
        val spec = specOrNull(id) ?: return
        set(id, ModelStatus.Downloading(currentBytes(spec), spec.size, waiting = "Waiting…"))
        DownloadService.start(context)
        jobs[id] = context.app.scope.launch(Dispatchers.IO) {
            try {
                queue.withLock { runDownload(spec, baseUrl, token) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                set(id, (initialStatus(spec) as? ModelStatus.Downloading)?.copy(paused = true) ?: ModelStatus.NotInstalled)
            } catch (e: Throwable) {
                app.carnet.diag.Diag.report(e, "model.download", mapOf("model" to id))
                set(id, ModelStatus.Failed(app.carnet.diag.Diag.friendly(e, "download")))
            }
        }
    }

    private fun currentBytes(spec: ModelSpec) = spec.files.sumOf { f -> File(dir(spec.id), f.name).takeIf { it.exists() && it.length() == f.size }?.length() ?: File(dir(spec.id), f.name + ".part").length() }

    private suspend fun runDownload(spec: ModelSpec, baseUrl: String?, token: String?) {
        val id = spec.id
        val d = dir(id).apply { mkdirs() }
        File(d, "spec.json").writeText(spec.toJson().toString())
        var done = currentBytes(spec)
        val meter = SpeedMeter()
        for (f in spec.files) {
            val target = File(d, f.name)
            if (target.exists() && target.length() == f.size) continue
            val part = File(d, f.name + ".part")
            val url = if (baseUrl != null) "$baseUrl/file/$id/${f.name}" else f.url
            var attempt = 0
            while (true) {
                if (baseUrl == null) awaitAllowedNetwork(id, done, spec.size)
                try {
                    set(id, ModelStatus.Downloading(done, spec.size, speed = 0))
                    val job = kotlin.coroutines.coroutineContext[Job]
                    fetch(url, part, token, { job?.isActive != false }) { n ->
                        done += n
                        set(id, ModelStatus.Downloading(done, spec.size, speed = meter.add(n)))
                    }
                    break
                } catch (e: IOException) {
                    // Lost connection: wait and resume from where it stopped.
                    if (++attempt > 8) throw IOException("Connection lost — tap to resume")
                    done = currentBytes(spec)
                    val wait = minOf(60, 2 shl minOf(attempt, 5))
                    for (s in wait downTo 1) { set(id, ModelStatus.Downloading(done, spec.size, waiting = "Reconnecting in ${s}s")); delay(1000) }
                }
            }
            set(id, ModelStatus.Verifying)
            if (sha256(part) != f.sha256) { part.delete(); throw IllegalStateException("${f.name} was damaged in transfer — try again") }
            part.renameTo(target)
            done = currentBytes(spec)
        }
        File(d, ".ok").writeText("ok")
        _generation.value++
        set(id, ModelStatus.Installed)
    }

    /** Respects "Wi-Fi only": waits (visibly) until the phone is on an unmetered network. */
    private suspend fun awaitAllowedNetwork(id: String, done: Long, total: Long) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        while (context.app.settings.flow.first().wifiOnly && cm.isActiveNetworkMetered) {
            set(id, ModelStatus.Downloading(done, total, waiting = "Waiting for Wi-Fi"))
            delay(3000)
        }
    }

    fun pause(id: String) { jobs[id]?.cancel() }
    fun pauseAll() { jobs.values.forEach { it.cancel() } }

    fun delete(id: String) {
        jobs[id]?.cancel()
        dir(id).deleteRecursively()
        set(id, ModelStatus.NotInstalled)
    }

    private fun httpText(url: String): String {
        var u = url
        repeat(8) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 15000; c.readTimeout = 20000
            val code = c.responseCode
            if (code in 300..399) { u = URL(URL(u), c.getHeaderField("Location")).toString(); c.disconnect(); return@repeat }
            if (code != 200) throw IOException("Server answered $code")
            return c.inputStream.use { String(it.readBytes()) }
        }
        throw IOException("Too many redirects")
    }

    private fun fetch(url: String, part: File, token: String?, active: () -> Boolean, onBytes: (Long) -> Unit) {
        var redirects = 0
        var u = url
        while (true) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000; c.readTimeout = 30000
            if (token != null) c.setRequestProperty("X-Carnet-Token", token)
            val have = if (part.exists()) part.length() else 0L
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code in 300..399 && redirects++ < 8) { u = URL(URL(u), c.getHeaderField("Location")).toString(); c.disconnect(); continue }
            if (code == 416) return // already complete
            if (code != 200 && code != 206) throw IllegalStateException("Server answered $code")
            val append = code == 206
            if (!append && have > 0) { onBytes(-have); part.delete() }
            c.inputStream.use { input -> java.io.FileOutputStream(part, append).use { out -> copyCounting(input, out, active, onBytes) } }
            return
        }
    }

    private fun copyCounting(input: InputStream, out: java.io.OutputStream, active: () -> Boolean, onBytes: (Long) -> Unit) {
        val buf = ByteArray(1 shl 16)
        var pending = 0L
        var last = System.currentTimeMillis()
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            pending += n
            val now = System.currentTimeMillis()
            if (now - last > 250) { onBytes(pending); pending = 0; last = now }
            if (!active()) throw kotlinx.coroutines.CancellationException("paused")
        }
        onBytes(pending)
    }

    /** Imports model files picked by the user (e.g. received via Quick Share or copied over USB). */
    suspend fun importFiles(uris: List<Uri>): List<String> = withContext(Dispatchers.IO) {
        val imported = ArrayList<String>()
        for (uri in uris) {
            val tmp = File(context.cacheDir, "import.tmp")
            context.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 16) } }
            val hash = sha256(tmp)
            val match = catalog.flatMap { s -> s.files.map { s to it } }.firstOrNull { it.second.sha256 == hash }
            if (match == null) { tmp.delete(); continue }
            val (spec, f) = match
            dir(spec.id).mkdirs()
            File(dir(spec.id), "spec.json").writeText(spec.toJson().toString())
            tmp.copyTo(file(spec.id, f.name), true); tmp.delete()
            if (spec.files.all { file(spec.id, it.name).let { x -> x.exists() && x.length() == it.size } }) { File(dir(spec.id), ".ok").writeText("ok"); set(spec.id, ModelStatus.Installed) }
            imported.add(spec.title)
        }
        imported
    }

    /** Makes a model offered by another phone known here (it may be newer than this phone's catalog). */
    fun adopt(spec: ModelSpec) {
        if (specOrNull(spec.id) != null) return
        dir(spec.id).mkdirs()
        File(dir(spec.id), "spec.json").writeText(spec.toJson().toString())
        _catalog.value = loadCatalog()
        set(spec.id, initialStatus(spec))
    }

    /** Manifest served to other phones: installed models with everything needed to install them. */
    fun manifest(): JSONObject {
        val arr = JSONArray()
        for (s in catalog.filter { isInstalled(it.id) }) arr.put(s.toJson())
        return JSONObject().put("app", "carnet").put("version", 2).put("models", arr)
    }

    private class SpeedMeter {
        private val window = ArrayDeque<Pair<Long, Long>>()
        fun add(bytes: Long): Long {
            val now = System.currentTimeMillis()
            window.addLast(now to bytes)
            while (window.size > 2 && now - window.first().first > 4000) window.removeFirst()
            val span = (now - window.first().first).coerceAtLeast(250)
            return window.sumOf { it.second } * 1000 / span
        }
    }

    companion object {
        const val CATALOG_URL = "https://github.com/openera-co/deepl/releases/download/models-v2/models.json"

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
