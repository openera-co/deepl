package app.carnet.record

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.carnet.CarnetApp
import app.carnet.R
import app.carnet.app
import app.carnet.core.text.Dictation
import app.carnet.data.ItemType
import app.carnet.data.LibraryItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.sqrt

data class Segment(val startMs: Long, val french: String, val english: String? = null, val marked: Boolean = false)

data class RecState(
    val recording: Boolean = false,
    val paused: Boolean = false,
    val startedAt: Long = 0,
    val pausedTotal: Long = 0,
    val pausedAt: Long = 0,
    val segments: List<Segment> = emptyList(),
    val partial: String = "",
    val level: Float = 0f,
    val levels: List<Float> = emptyList(),
    val title: String = "Dictation",
    val savedId: String? = null,
    val audio: Boolean = false,
    val error: String? = null,
    val recognizer: Boolean = true,
    /** Who turns speech into text: the phone's speech service, or Carnet's own offline model. */
    val engine: SpeechEngine = SpeechEngine.PHONE,
    /** The phone can't recognise this language and the offline speech model isn't downloaded yet. */
    val needsSpeechModel: Boolean = false,
) {
    val elapsedMs: Long get() = if (!recording) 0 else ((if (paused) pausedAt else System.currentTimeMillis()) - startedAt - pausedTotal)
}

enum class SpeechEngine { PHONE, CARNET }

/**
 * Live dictation. Audio is recorded to WAV (for "Improve" later). Speech becomes text through, in
 * order: the phone's on-device recogniser, the phone's regular recogniser, and finally Carnet's own
 * offline model (whisper), which works on any phone once downloaded. Switching happens on its own,
 * mid-recording, without losing audio.
 */
object Recorder {
    const val LIVE_MODEL = "whisper-small"
    private val _state = MutableStateFlow(RecState())
    val state: StateFlow<RecState> = _state
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var audio: AudioRecord? = null
    private var audioThread: Thread? = null
    private var pipeWrite: ParcelFileDescriptor.AutoCloseOutputStream? = null
    private var wavFile: File? = null
    private var dir: File? = null
    private var segStart = 0L
    private var failures = 0
    /** Phone recogniser attempts: the on-device one first, then the regular one. */
    private var onDevice = true
    private lateinit var appCtx: Context
    /** Spoken language: English only when the user chose English → French; school dictation is French otherwise. */
    @Volatile var lang = "fr"; private set
    private const val RATE = 16000

    // Carnet's offline engine: audio thread → live buffer → worker thread.
    @Volatile private var live = false
    private val liveBuf = ArrayList<FloatArray>()
    private var liveSamples = 0
    private var liveStartMs = 0L
    @Volatile private var quietBlocks = 0
    @Volatile private var noiseFloor = 0.01f
    private var worker: Thread? = null
    /** Bumped at every start and stop, so a worker still finishing can't touch another recording. */
    @Volatile private var session = 0

    fun available(c: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(c) ||
        (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(c))

    private fun offlineReady(c: Context) = app.carnet.nativeai.NativeLib.whisperLoaded &&
        c.app.models.isInstalled(LIVE_MODEL)

    /** The offline speech model can run in this build (shown as an option when the phone can't). */
    fun offlinePossible(c: Context) = c.app.models.supported(LIVE_MODEL)

    @SuppressLint("MissingPermission")
    fun start(c: Context, title: String) {
        if (_state.value.recording) return
        appCtx = c.applicationContext
        lang = if (appCtx.app.engine.direction == "en-fr") "en" else "fr"
        failures = 0; onDevice = true; session++
        appCtx.app.scope.launch { punct = appCtx.app.settings.flow.first().spokenPunctuation }
        val id = appCtx.app.library.newId()
        dir = appCtx.app.library.dir(id)
        val phone = available(c)
        // The phone's recogniser keeps its own microphone below Android 13; otherwise we share ours.
        val ownMic = Build.VERSION.SDK_INT >= 33 || !phone
        _state.value = RecState(recording = true, startedAt = System.currentTimeMillis(), title = title, savedId = id, audio = ownMic, recognizer = phone)
        if (ownMic) startAudio()
        if (phone) main.post { listen() } else goOffline()
    }

    private fun startAudio() {
        if (audio != null) return
        val f = wavFile ?: File(dir, "audio.wav").also { wavFile = it }
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @SuppressLint("MissingPermission")
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
        audio = rec
        rec.startRecording()
        _state.value = _state.value.copy(audio = true)
        val append = f.exists() && f.length() > 44
        audioThread = Thread {
            FileOutputStream(f, append).use { out ->
                if (!append) out.write(ByteArray(44)) // header written on stop
                val buf = ShortArray(RATE / 10)
                val bytes = ByteArray(buf.size * 2)
                while (_state.value.recording && audio === rec) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (_state.value.paused) continue
                    var sum = 0.0
                    for (i in 0 until n) { val v = buf[i].toInt(); sum += v * v; bytes[2 * i] = (v and 0xFF).toByte(); bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte() }
                    out.write(bytes, 0, n * 2)
                    try { pipeWrite?.write(bytes, 0, n * 2) } catch (_: Exception) {}
                    val rms = sqrt(sum / n)
                    level((rms / 6000.0).toFloat().coerceIn(0f, 1f))
                    if (live) feed(buf, n, (rms / 32768.0).toFloat())
                }
            }
        }.apply { name = "carnet-audio"; start() }
    }

    private fun level(l: Float) {
        val st = _state.value
        _state.value = st.copy(level = l, levels = (st.levels + l).takeLast(48))
    }

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (lang == "en") "en-GB" else "fr-FR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        if (Build.VERSION.SDK_INT >= 33 && audio != null) {
            val pipe = ParcelFileDescriptor.createPipe()
            pipeWrite?.runCatching { close() }
            pipeWrite = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
    }

    private fun listen() {
        val st = _state.value
        if (!st.recording || st.paused || st.engine != SpeechEngine.PHONE || !st.recognizer) return
        val useOnDevice = onDevice && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(appCtx)
        if (!useOnDevice && !SpeechRecognizer.isRecognitionAvailable(appCtx)) { goOffline(); return }
        val sr = recognizer ?: (if (useOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(appCtx)
        else SpeechRecognizer.createSpeechRecognizer(appCtx)).also { recognizer = it }
        sr.setRecognitionListener(listener)
        segStart = _state.value.elapsedMs
        try { sr.startListening(intent()) } catch (e: Exception) { app.carnet.diag.Diag.report(e, "record.listen"); nextRecognizer() }
    }

    /** The current phone recogniser can't do it: try the regular one, then Carnet's own. */
    private fun nextRecognizer() {
        recognizer?.runCatching { destroy() }; recognizer = null
        failures = 0
        if (onDevice && SpeechRecognizer.isRecognitionAvailable(appCtx)) {
            onDevice = false
            app.carnet.diag.Diag.breadcrumb("record: on-device recogniser unavailable, using the regular one")
            main.postDelayed({ listen() }, 150)
        } else goOffline()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { segStart = _state.value.elapsedMs }
        override fun onRmsChanged(rmsdB: Float) { if (audio == null) level(((rmsdB + 2) / 12f).coerceIn(0f, 1f)) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            if (_state.value.engine != SpeechEngine.PHONE) return
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { _state.value = _state.value.copy(error = "Carnet needs the microphone. Allow it in Settings."); return }
            if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                // Ask Android to fetch the language for next time; carry on with the next option now.
                if (Build.VERSION.SDK_INT >= 33 && onDevice) runCatching { recognizer?.triggerModelDownload(intent()) }
                nextRecognizer(); return
            }
            val normal = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (!normal) failures++
            // Some recognisers can't take our shared audio stream: let them use their own microphone.
            if (failures == 4 && audio != null && Build.VERSION.SDK_INT >= 33) {
                stopAudio()
                _state.value = _state.value.copy(audio = false)
            } else if (failures >= 8) { nextRecognizer(); return }
            // No match / timeout are normal pauses in dictation: just listen again.
            main.postDelayed({ restart() }, if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 400 else 60)
        }
        override fun onResults(results: Bundle?) { failures = 0; commit(results); main.post { restart() } }
        override fun onSegmentResults(segmentResults: Bundle) { failures = 0; commit(segmentResults) }
        override fun onEndOfSegmentedSession() { main.post { restart() } }
        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            _state.value = _state.value.copy(partial = t)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun restart() {
        if (!_state.value.recording || _state.value.paused || _state.value.engine != SpeechEngine.PHONE) return
        recognizer?.cancel()
        listen()
    }

    private fun stopAudio() {
        val a = audio ?: return
        audio = null
        audioThread?.join(1500); audioThread = null
        a.runCatching { stop(); release() }
        pipeWrite?.runCatching { close() }; pipeWrite = null
    }

    /**
     * Switches to Carnet's own speech model. Called when the phone can't recognise the language, and
     * again from the screen once the model has finished downloading.
     */
    fun goOffline() {
        if (!_state.value.recording) return
        main.post { recognizer?.runCatching { destroy() }; recognizer = null }
        pipeWrite?.runCatching { close() }; pipeWrite = null
        if (!offlineReady(appCtx)) {
            _state.value = _state.value.copy(engine = SpeechEngine.CARNET, needsSpeechModel = true, partial = "", error = null)
            startAudio()
            return
        }
        _state.value = _state.value.copy(engine = SpeechEngine.CARNET, needsSpeechModel = false, recognizer = true, partial = "", error = null)
        startAudio()
        if (worker?.isAlive == true && live) return
        synchronized(liveBuf) { liveBuf.clear(); liveSamples = 0 }
        live = true
        val mine = session
        worker = Thread { runLive(mine) }.apply { name = "carnet-live"; start() }
    }

    private fun feed(buf: ShortArray, n: Int, rms: Float) {
        // Slowly-adapting noise floor: a block is quiet when it's close to the room's background.
        noiseFloor = if (rms < noiseFloor) rms.coerceAtLeast(0.002f) else noiseFloor * 0.995f + rms * 0.005f
        val quiet = rms < maxOf(0.008f, noiseFloor * 2.2f)
        quietBlocks = if (quiet) quietBlocks + 1 else 0
        val f = FloatArray(n) { buf[it] / 32768f }
        synchronized(liveBuf) {
            // Don't keep long silences: only the last half second before speech starts.
            if (liveSamples == 0 && quiet) return
            if (liveSamples == 0) liveStartMs = (_state.value.elapsedMs - 100).coerceAtLeast(0)
            liveBuf.add(f); liveSamples += n
        }
    }

    private fun takeChunk(): Pair<FloatArray, Long>? = synchronized(liveBuf) {
        val secs = liveSamples / RATE.toFloat()
        val pause = quietBlocks >= 6
        if (!(secs >= (if (_state.value.recording) 2f else 0.5f) && pause) && secs < 10f) return null
        val out = FloatArray(liveSamples); var o = 0
        for (b in liveBuf) { b.copyInto(out, o); o += b.size }
        liveBuf.clear(); liveSamples = 0
        out to liveStartMs
    }

    private fun runLive(mine: Int) {
        val mm = appCtx.app.models
        val model = LIVE_MODEL
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val h = runCatching { app.carnet.nativeai.NativeLib.whisperInit(mm.file(model, "model.bin").path, threads) }.getOrDefault(0L)
        if (h == 0L) {
            _state.value = _state.value.copy(error = "Live captions couldn't start. Audio is still being recorded.")
            live = false; return
        }
        var prompt = ""
        try {
            while (session == mine && (_state.value.recording || synchronized(liveBuf) { liveSamples } > RATE)) {
                // After Stop, the words still buffered are transcribed once more before finishing.
                if (!_state.value.recording) quietBlocks = 99
                val chunk = takeChunk()
                if (chunk == null) {
                    val speaking = synchronized(liveBuf) { liveSamples } > RATE / 2
                    if (speaking != _state.value.partial.isNotEmpty()) _state.value = _state.value.copy(partial = if (speaking) "…" else "")
                    Thread.sleep(150); continue
                }
                val (pcm, startMs) = chunk
                val t0 = System.nanoTime()
                val raw = runCatching { app.carnet.nativeai.NativeLib.whisperLive(h, pcm, lang, prompt) }
                    .onFailure { app.carnet.diag.Diag.report(it, "record.live") }.getOrDefault("")
                val text = cleanLive(raw)
                app.carnet.diag.Diag.breadcrumb("live chunk ${pcm.size / RATE}s in ${(System.nanoTime() - t0) / 1_000_000}ms")
                // A slow phone may still be finishing after Stop: never write into the next recording.
                if (session != mine) break
                if (text.isNotEmpty()) { prompt = text.takeLast(180); commitText(text, startMs) }
                if (synchronized(liveBuf) { liveSamples } == 0) _state.value = _state.value.copy(partial = "")
            }
        } finally {
            app.carnet.nativeai.NativeLib.whisperFree(h)
            if (session == mine) live = false
        }
    }

    /** Drops what speech models invent on silence or noise (credits, "thanks for watching", [music]). */
    private val junk = Regex("(?i)(sous-titr|amara\\.org|merci d'avoir regardé|thanks for watching|abonnez-vous|^\\W*$)")
    internal fun cleanLive(raw: String): String {
        val t = raw.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)|\\*[^*]*\\*"), " ").replace(Regex("\\s+"), " ").trim()
        return if (t.length < 2 || junk.containsMatchIn(t)) "" else collapseRepeats(t)
    }

    /** "des des des des" / "et qui signifie X et qui signifie X …" → said once. */
    internal fun collapseRepeats(t: String): String {
        val w = t.split(' ').toMutableList()
        for (n in 1..8) {
            var i = 0
            while (i + 2 * n <= w.size) {
                var reps = 1
                while (i + (reps + 1) * n <= w.size && (0 until n).all { k -> w[i + k].equals(w[i + reps * n + k], true) }) reps++
                if (reps >= 3) repeat((reps - 1) * n) { w.removeAt(i + n) }
                i++
            }
        }
        return w.joinToString(" ")
    }

    private fun commit(b: Bundle?) {
        val raw = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
        if (raw.isEmpty()) { _state.value = _state.value.copy(partial = ""); return }
        commitText(raw, segStart)
    }

    @Volatile private var punct = true

    @Synchronized private fun commitText(raw: String, start: Long) {
        val fr = if (lang == "fr") Dictation.format(raw, punct) else raw.replaceFirstChar { it.uppercase() }.let { if (it.last() in ".!?") it else "$it." }
        val st = _state.value
        val idx = st.segments.size
        _state.value = st.copy(segments = st.segments + Segment(start, fr), partial = if (st.engine == SpeechEngine.CARNET) st.partial else "")
        appCtx.app.scope.launch {
            val en = runCatching { appCtx.app.engine.translate(fr, if (lang == "en") "en-fr" else "fr-en") }.getOrNull()
            val cur = _state.value
            if (idx < cur.segments.size) _state.value = cur.copy(segments = cur.segments.toMutableList().also { it[idx] = it[idx].copy(english = en) })
        }
    }

    fun pause() {
        val st = _state.value
        if (!st.recording || st.paused) return
        _state.value = st.copy(paused = true, pausedAt = System.currentTimeMillis())
        recognizer?.stopListening()
    }

    fun resume() {
        val st = _state.value
        if (!st.paused) return
        _state.value = st.copy(paused = false, pausedTotal = st.pausedTotal + (System.currentTimeMillis() - st.pausedAt))
        if (st.engine == SpeechEngine.PHONE) main.post { listen() }
    }

    fun mark() {
        val st = _state.value
        if (st.segments.isEmpty()) _state.value = st.copy(segments = listOf(Segment(st.elapsedMs, "", marked = true)))
        else _state.value = st.copy(segments = st.segments.dropLast(1) + st.segments.last().copy(marked = true))
    }

    /** Stops and saves. Returns the library id. */
    suspend fun stop(): String? {
        val st = _state.value
        if (!st.recording) return null
        val duration = st.elapsedMs
        _state.value = st.copy(recording = false)
        main.post { recognizer?.destroy(); recognizer = null }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { stopAudio(); worker?.join(15000); session++; live = false }
        // Words from the last few seconds the offline engine was still working on.
        val finalSegs = _state.value.segments.takeIf { it.size >= st.segments.size } ?: st.segments
        wavFile?.let { writeWavHeader(it) }
        val id = st.savedId ?: return null
        val dir = appCtx.app.library.dir(id)
        File(dir, "lang").writeText(lang)
        saveTranscript(File(dir, "transcript.json"), finalSegs)
        val meta = mapOf("duration" to duration.toString(), "segments" to finalSegs.size.toString(), "audio" to (wavFile != null).toString())
        appCtx.app.library.put(LibraryItem(id, ItemType.RECORDING, st.title, System.currentTimeMillis(), wavFile?.name, "transcript.json", meta))
        wavFile = null; this.dir = null
        _state.value = RecState(savedId = id)
        return id
    }

    fun saveTranscript(f: File, segs: List<Segment>) {
        val a = JSONArray()
        for (s in segs) a.put(JSONObject().put("t", s.startMs).put("fr", s.french).put("en", s.english ?: JSONObject.NULL).put("m", s.marked))
        f.writeText(a.toString())
    }

    fun loadTranscript(f: File): List<Segment> = runCatching {
        val a = JSONArray(f.readText())
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Segment(o.getLong("t"), o.getString("fr"), o.optString("en").takeIf { e -> e.isNotEmpty() && e != "null" }, o.optBoolean("m")) } }
    }.getOrDefault(emptyList())

    private fun writeWavHeader(f: File) {
        val dataLen = f.length() - 44
        RandomAccessFile(f, "rw").use { r ->
            fun i32(v: Int) = byteArrayOf((v and 255).toByte(), ((v shr 8) and 255).toByte(), ((v shr 16) and 255).toByte(), ((v shr 24) and 255).toByte())
            fun i16(v: Int) = byteArrayOf((v and 255).toByte(), ((v shr 8) and 255).toByte())
            r.seek(0)
            r.write("RIFF".toByteArray()); r.write(i32((dataLen + 36).toInt())); r.write("WAVE".toByteArray())
            r.write("fmt ".toByteArray()); r.write(i32(16)); r.write(i16(1)); r.write(i16(1)); r.write(i32(RATE)); r.write(i32(RATE * 2)); r.write(i16(2)); r.write(i16(16))
            r.write("data".toByteArray()); r.write(i32(dataLen.toInt()))
        }
    }

    @Suppress("unused") private fun silence(buf: ShortArray, n: Int) = (0 until n).all { abs(buf[it].toInt()) < 300 }
}

/** Foreground service so dictation continues with the screen off. */
class RecordService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = NotificationCompat.Builder(this, CarnetApp.CH_RECORD).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Recording").setContentText("Carnet is transcribing the lesson").setOngoing(true).build()
        ServiceCompat.startForeground(this, 1, n, if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        return START_NOT_STICKY
    }

    companion object {
        fun start(c: Context) = androidx.core.content.ContextCompat.startForegroundService(c, Intent(c, RecordService::class.java))
        fun stop(c: Context) = c.stopService(Intent(c, RecordService::class.java))
    }
}
