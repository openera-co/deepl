package app.carnet.nativeai

import app.carnet.core.mt.MarianText
import app.carnet.core.mt.ModelSource
import app.carnet.core.mt.PiecesTranslator
import java.io.Closeable
import java.io.File

/** JNI bridge to CTranslate2 (native-ct2). */
object Ct2Lib {
    val loaded: Boolean by lazy { runCatching { System.loadLibrary("carnet_ct2") }.isSuccess }
    @JvmStatic external fun init(dir: String, threads: Int): Long
    @JvmStatic external fun translate(handle: Long, batch: Array<ByteArray>, beam: Int, maxLen: Int): Array<ByteArray>
    @JvmStatic external fun free(handle: Long)
}

/**
 * A translation model run by CTranslate2: the model's folder holds model.bin + config.json +
 * vocabulary (CTranslate2's conversion) and the app's own pieces/vocab files for tokenisation.
 */
class Ct2Model(dir: File, source: ModelSource, threads: Int) : Closeable {
    val text = MarianText(source)
    private val handle = Ct2Lib.init(dir.path, threads)
    @Volatile private var closed = false

    private fun run(batch: List<List<String>>, beam: Int): List<List<String>> {
        check(!closed) { "model closed" }
        val sep = '\u001f'
        val input = Array(batch.size) { batch[it].joinToString(sep.toString()).toByteArray(Charsets.UTF_8) }
        val maxLen = (batch.maxOf { it.size } * 2 + 16).coerceAtMost(512)
        val out = synchronized(this) { Ct2Lib.translate(handle, input, beam, maxLen) }
        return out.map { b -> String(b, Charsets.UTF_8).let { if (it.isEmpty()) emptyList() else it.split(sep) } }
    }

    fun translator(beam: Int = 1) = PiecesTranslator(text, beam) { b, bm -> run(b, bm) }

    override fun close() { synchronized(this) { if (!closed) { closed = true; Ct2Lib.free(handle) } } }

    companion object {
        fun available(dir: File) = Ct2Lib.loaded && File(dir, "model.bin").exists()
    }
}
