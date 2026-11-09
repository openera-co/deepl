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
) {
    val elapsedMs: Long get() = if (!recording) 0 else ((if (paused) pausedAt else System.currentTimeMillis()) - startedAt - pausedTotal)
}

/**
 * Live dictation: audio is recorded to WAV (for "Improve" later) and, on Android 13+, the same
 * audio stream feeds the on-device speech recogniser. Older phones use the recogniser's own mic.
 */
object Recorder {
    private val _state = MutableStateFlow(RecState())
    val state: StateFlow<RecState> = _state
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var audio: AudioRecord? = null
    private var audioThread: Thread? = null
    private var pipeWrite: ParcelFileDescriptor.AutoCloseOutputStream? = null
    private var wavFile: File? = null
    private var segStart = 0L
    private var failures = 0
    private lateinit var appCtx: Context
    /** Spoken language: English only when the user chose English → French; school dictation is French otherwise. */
    @Volatile var lang = "fr"; private set
    private const val RATE = 16000

    fun available(c: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(c) ||
        (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(c))

    @SuppressLint("MissingPermission")
    fun start(c: Context, title: String) {
        if (_state.value.recording) return
        appCtx = c.applicationContext
        lang = if (appCtx.app.engine.direction == "en-fr") "en" else "fr"
        failures = 0
        val id = appCtx.app.library.newId()
        val dir = appCtx.app.library.dir(id)
        val shareMic = Build.VERSION.SDK_INT >= 33
        _state.value = RecState(recording = true, startedAt = System.currentTimeMillis(), title = title, savedId = id, audio = shareMic, recognizer = available(c))
        if (shareMic) {
            wavFile = File(dir, "audio.wav")
            startAudio(wavFile!!)
        }
        main.post { listen() }
    }

    private fun startAudio(f: File) {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @SuppressLint("MissingPermission")
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
        audio = rec
        rec.startRecording()
        audioThread = Thread {
            FileOutputStream(f).use { out ->
                out.write(ByteArray(44)) // header written on stop
                val buf = ShortArray(RATE / 10)
                val bytes = ByteArray(buf.size * 2)
                while (_state.value.recording) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (_state.value.paused) continue
                    var sum = 0.0
                    for (i in 0 until n) { val v = buf[i].toInt(); sum += v * v; bytes[2 * i] = (v and 0xFF).toByte(); bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte() }
                    out.write(bytes, 0, n * 2)
                    try { pipeWrite?.write(bytes, 0, n * 2) } catch (_: Exception) {}
                    level((sqrt(sum / n) / 6000.0).toFloat().coerceIn(0f, 1f))
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
        if (!_state.value.recording || _state.value.paused) return
        if (!_state.value.recognizer) return
        val sr = recognizer ?: (if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(appCtx)) SpeechRecognizer.createOnDeviceSpeechRecognizer(appCtx)
        else SpeechRecognizer.createSpeechRecognizer(appCtx)).also { recognizer = it }
        sr.setRecognitionListener(listener)
        segStart = _state.value.elapsedMs
        try { sr.startListening(intent()) } catch (e: Exception) { _state.value = _state.value.copy(error = e.message) }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { segStart = _state.value.elapsedMs }
        override fun onRmsChanged(rmsdB: Float) { if (audio == null) level(((rmsdB + 2) / 12f).coerceIn(0f, 1f)) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            val normal = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (!normal) failures++
            // Some recognisers can't take our shared audio stream: fall back to their own microphone.
            if (failures >= 4 && audio != null) {
                audio?.runCatching { stop(); release() }; audio = null
                pipeWrite?.runCatching { close() }; pipeWrite = null
                _state.value = _state.value.copy(audio = false)
                failures = 0
            } else if (failures >= 8) {
                _state.value = _state.value.copy(error = "Speech recognition isn't working on this phone. Audio is still being kept if possible.", recognizer = false)
                return
            }
            // No match / timeout are normal pauses in dictation: just listen again.
            if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                _state.value = _state.value.copy(error = "French speech recognition isn't installed on this phone", recognizer = false)
                return
            }
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { _state.value = _state.value.copy(error = "Microphone permission needed"); return }
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
        if (!_state.value.recording || _state.value.paused) return
        recognizer?.cancel()
        listen()
    }

    private fun commit(b: Bundle?) {
        val raw = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
        if (raw.isEmpty()) { _state.value = _state.value.copy(partial = ""); return }
        appCtx.app.scope.launch {
            val punct = appCtx.app.settings.flow.first().spokenPunctuation
            val fr = if (lang == "fr") Dictation.format(raw, punct) else raw.replaceFirstChar { it.uppercase() }.let { if (it.last() in ".!?") it else "$it." }
            val st = _state.value
            val idx = st.segments.size
            _state.value = st.copy(segments = st.segments + Segment(segStart, fr), partial = "")
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
        main.post { listen() }
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
        audio?.runCatching { stop(); release() }; audio = null
        audioThread?.join(1500); audioThread = null
        pipeWrite?.runCatching { close() }; pipeWrite = null
        wavFile?.let { writeWavHeader(it) }
        val id = st.savedId ?: return null
        val dir = appCtx.app.library.dir(id)
        saveTranscript(File(dir, "transcript.json"), st.segments)
        val meta = mapOf("duration" to duration.toString(), "segments" to st.segments.size.toString(), "audio" to (wavFile != null).toString())
        appCtx.app.library.put(LibraryItem(id, ItemType.RECORDING, st.title, System.currentTimeMillis(), wavFile?.name, "transcript.json", meta))
        wavFile = null
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
