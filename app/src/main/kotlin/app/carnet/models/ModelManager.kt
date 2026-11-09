package app.carnet.models

import android.content.Context
import android.net.Uri
import app.carnet.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
    val builtIn: Boolean = false,
    val builtInSize: Long = 0,
) {
    val size: Long get() = if (builtIn) builtInSize else files.sumOf { it.size }
}

sealed class ModelStatus {
    object NotInstalled : ModelStatus()
    data class Downloading(val done: Long, val total: Long, val paused: Boolean = false) : ModelStatus()
    object Verifying : ModelStatus()
    object Installed : ModelStatus()
    data class Failed(val reason: String) : ModelStatus()
}

/**
 * Optional on-device models: download (resumable, checksummed), import from a file,
 * and export/share with other phones (see ShareServer).
 */
class ModelManager(private val context: Context) {
    val catalog = listOf(
        ModelSpec("translate", "Translation", "French → English", emptyList(), builtIn = true, builtInSize = 105L shl 20),
        ModelSpec("ocr", "Printed text", "Reading photos", emptyList(), builtIn = true, builtInSize = 4L shl 20),
        ModelSpec("handwriting", "Handwriting & Improve", "TranslateGemma 4B", listOf(
            ModelFile("model.gguf", "https://huggingface.co/mradermacher/translategemma-4b-it-GGUF/resolve/main/translategemma-4b-it.Q4_K_M.gguf", 2489909760, "81200d03e843d2ec1ece6eeafe7d13cb6e5211e1fcd336ade55790b683a08330"),
            ModelFile("vision.gguf", "https://huggingface.co/mradermacher/translategemma-4b-it-GGUF/resolve/main/translategemma-4b-it.mmproj-Q8_0.gguf", 591377600, "482f68be8823dfdfb3561c22cdd1c0f617d0d5e6efe9c323cc6428a4331dba01"),
        )),
        ModelSpec("whisper-small", "Dictation · Improve (fast)", "Whisper small", listOf(
            ModelFile("model.bin", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin", 190085487, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"),
        )),
        ModelSpec("whisper-turbo", "Dictation · Improve (best)", "Whisper large-v3 turbo", listOf(
            ModelFile("model.bin", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin", 574041195, "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"),
        )),
    )

    private val root = File(context.filesDir, "models").apply { mkdirs() }
    private val _status = MutableStateFlow(catalog.associate { it.id to initialStatus(it) })
    val status: StateFlow<Map<String, ModelStatus>> = _status
    private val jobs = HashMap<String, Job>()

    fun spec(id: String) = catalog.first { it.id == id }

    /** Optional models need their native engine in this build and a compatible CPU. */
    fun supported(id: String): Boolean = when {
        id == "handwriting" -> app.carnet.nativeai.NativeLib.llamaLoaded
        id.startsWith("whisper") -> app.carnet.nativeai.NativeLib.whisperLoaded
        else -> true
    }
    fun dir(id: String) = File(root, id)
    fun file(id: String, name: String) = File(dir(id), name)
    fun isInstalled(id: String) = _status.value[id] is ModelStatus.Installed

    private fun initialStatus(s: ModelSpec): ModelStatus = when {
        s.builtIn -> ModelStatus.Installed
        s.files.all { File(File(root, s.id), it.name).let { f -> f.exists() && f.length() == it.size } } && File(File(root, s.id), ".ok").exists() -> ModelStatus.Installed
        s.files.any { File(File(root, s.id), it.name + ".part").exists() } ->
            ModelStatus.Downloading(s.files.sumOf { File(File(root, s.id), it.name + ".part").length() + File(File(root, s.id), it.name).let { f -> if (f.exists()) f.length() else 0 } }, s.size, paused = true)
        else -> ModelStatus.NotInstalled
    }

    private fun set(id: String, st: ModelStatus) { _status.value = _status.value + (id to st) }

    fun installedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Downloads (or resumes) all files of a model from its origin. */
    fun download(id: String, baseUrl: String? = null, token: String? = null) {
        if (jobs[id]?.isActive == true) return
        val spec = spec(id)
        jobs[id] = context.app.scope.launch(Dispatchers.IO) {
            try {
                val d = dir(id).apply { mkdirs() }
                var done = spec.files.sumOf { f -> File(d, f.name).takeIf { it.exists() && it.length() == f.size }?.length() ?: File(d, f.name + ".part").length() }
                set(id, ModelStatus.Downloading(done, spec.size))
                for (f in spec.files) {
                    val target = File(d, f.name)
                    if (target.exists() && target.length() == f.size) continue
                    val part = File(d, f.name + ".part")
                    val url = if (baseUrl != null) "$baseUrl/file/$id/${f.name}" else f.url
                    val job = coroutineContext[Job]
                    fetch(url, part, token, { job?.isActive != false }) { n -> done += n; set(id, ModelStatus.Downloading(done, spec.size)) }
                    set(id, ModelStatus.Verifying)
                    if (sha256(part) != f.sha256) { part.delete(); throw IllegalStateException("Checksum mismatch for ${f.name} — download it again") }
                    part.renameTo(target)
                }
                File(d, ".ok").writeText("ok")
                set(id, ModelStatus.Installed)
            } catch (e: kotlinx.coroutines.CancellationException) {
                set(id, (initialStatus(spec) as? ModelStatus.Downloading)?.copy(paused = true) ?: ModelStatus.NotInstalled)
            } catch (e: Throwable) {
                set(id, ModelStatus.Failed(e.message ?: "Download failed"))
            }
        }
    }

    fun pause(id: String) { jobs[id]?.cancel() }

    fun delete(id: String) {
        jobs[id]?.cancel()
        dir(id).deleteRecursively()
        set(id, ModelStatus.NotInstalled)
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
            if (code != 200 && code != 206) throw IllegalStateException("Server answered $code")
            val append = code == 206
            if (!append && have > 0) part.delete()
            c.inputStream.use { input -> java.io.FileOutputStream(part, append).use { out -> copyCounting(input, out, active, onBytes) } }
            return
        }
    }

    private fun copyCounting(input: InputStream, out: java.io.OutputStream, active: () -> Boolean, onBytes: (Long) -> Unit) {
        val buf = ByteArray(1 shl 16)
        var pending = 0L
        var last = System.currentTimeMillis()
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
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
            tmp.copyTo(file(spec.id, f.name), true); tmp.delete()
            if (spec.files.all { file(spec.id, it.name).let { x -> x.exists() && x.length() == it.size } }) { File(dir(spec.id), ".ok").writeText("ok"); set(spec.id, ModelStatus.Installed) }
            imported.add(spec.title)
        }
        imported
    }

    /** Manifest served to other phones: installed models and their files. */
    fun manifest(): JSONObject {
        val arr = JSONArray()
        for (s in catalog.filter { !it.builtIn && isInstalled(it.id) }) {
            arr.put(JSONObject().put("id", s.id).put("title", s.title).put("size", s.size)
                .put("files", JSONArray(s.files.map { JSONObject().put("name", it.name).put("size", it.size).put("sha256", it.sha256) })))
        }
        return JSONObject().put("app", "carnet").put("version", 1).put("models", arr)
    }

    companion object {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
