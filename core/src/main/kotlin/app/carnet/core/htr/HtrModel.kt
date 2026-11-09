package app.carnet.core.htr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.carnet.core.mt.ModelSource
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Line-level handwriting recognizer (TrOCR, vision encoder + text decoder, ONNX with a merged
 * cached decoder). Input: one image per text line; output: its text. Exported by tools/export_htr.py:
 * encoder_model.int8.onnx, decoder_model_merged.int8.onnx, vocab.txt (one piece per id), htr.json.
 */
class HtrModel(private val source: ModelSource, threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val json = String(source.open("htr.json"))
    private fun int(k: String, d: Int) = Regex("\"$k\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toInt() ?: d
    private fun float(k: String, d: Float) = Regex("\"$k\"\\s*:\\s*(-?[\\d.]+)").find(json)?.groupValues?.get(1)?.toFloat() ?: d
    val size = int("size", 384)
    private val startId = int("decoder_start_id", 2); private val eosId = int("eos_id", 2); private val padId = int("pad_id", 1)
    private val layers = int("num_layers", 6); private val heads = int("num_heads", 8); private val headDim = int("head_dim", 32)
    private val mean = float("mean", 0.5f); private val std = float("std", 0.5f)
    private val vocab: Array<String> = String(source.open("vocab.txt"), Charsets.UTF_8).split('\n').map { it.replace("\\n", "\n") }.toTypedArray()
    private val opts = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads); setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private fun session(name: String): OrtSession = source.buffer(name)?.let { env.createSession(it, opts) }
        ?: source.path(name)?.let { env.createSession(it, opts) } ?: env.createSession(source.open(name), opts)
    private val encoder = session("encoder_model.int8.onnx")
    private val decoder = session("decoder_model_merged.int8.onnx")
    private val decInputs = decoder.inputNames

    /**
     * Converts an ARGB line image to the model input (size×size, normalised), written to [dst] at
     * [offset]. Antialiased bicubic resampling, like the training processor (PIL): a wide line is
     * shrunk a lot horizontally, and point sampling would lose thin strokes.
     */
    fun pixels(argb: IntArray, w: Int, h: Int, dst: FloatArray, offset: Int) {
        val s = size; val plane = s * s
        val wx = weights(w, s); val wy = weights(h, s)
        // Horizontal pass: h rows × s columns × 3 channels.
        val tmp = FloatArray(h * s * 3)
        for (y in 0 until h) for (x in 0 until s) {
            val (start, ws) = wx[x]
            var r = 0f; var g = 0f; var bl = 0f
            for (k in ws.indices) { val c = argb[y * w + start + k]; val wt = ws[k]; r += (c shr 16 and 255) * wt; g += (c shr 8 and 255) * wt; bl += (c and 255) * wt }
            val o = (y * s + x) * 3; tmp[o] = r; tmp[o + 1] = g; tmp[o + 2] = bl
        }
        // Vertical pass.
        for (y in 0 until s) {
            val (start, ws) = wy[y]
            for (x in 0 until s) for (ch in 0 until 3) {
                var v = 0f
                for (k in ws.indices) v += tmp[((start + k) * s + x) * 3 + ch] * ws[k]
                dst[offset + ch * plane + y * s + x] = (v.coerceIn(0f, 255f) / 255f - mean) / std
            }
        }
    }

    /** PIL-style bicubic (a = -0.5) resampling weights from [inSize] to [outSize], antialiased when shrinking. */
    private fun weights(inSize: Int, outSize: Int): Array<Pair<Int, FloatArray>> {
        val scale = inSize.toFloat() / outSize
        val support = 2f * max(1f, scale)
        val filterScale = max(1f, scale)
        fun cubic(x0: Float): Float {
            val x = kotlin.math.abs(x0); val a = -0.5f
            return when { x < 1f -> ((a + 2) * x - (a + 3)) * x * x + 1; x < 2f -> (((x - 5) * x + 8) * x - 4) * a; else -> 0f }
        }
        return Array(outSize) { i ->
            val center = (i + 0.5f) * scale
            val lo = max(0, (center - support + 0.5f).toInt()); val hi = min(inSize, (center + support + 0.5f).toInt())
            val ws = FloatArray(max(1, hi - lo)) { k -> cubic((lo + k - center + 0.5f) / filterScale) }
            val sum = ws.sum().takeIf { it != 0f } ?: 1f
            for (k in ws.indices) ws[k] /= sum
            lo to ws
        }
    }

    /** Recognizes a batch of line images (ARGB pixels with sizes). */
    fun recognize(lines: List<Triple<IntArray, Int, Int>>, maxTokens: Int = 96, cancel: () -> Boolean = { false }): List<String> {
        if (lines.isEmpty()) return emptyList()
        val out = ArrayList<String>(lines.size)
        for (chunk in lines.chunked(8)) {
            if (cancel()) throw InterruptedException("cancelled")
            out.addAll(decodeBatch(chunk, maxTokens, cancel))
        }
        return out
    }

    private fun decodeBatch(lines: List<Triple<IntArray, Int, Int>>, maxTokens: Int, cancel: () -> Boolean): List<String> {
        val b = lines.size; val s = size
        val px = FloatArray(b * 3 * s * s)
        lines.forEachIndexed { i, (a, w, h) -> pixels(a, max(1, w), max(1, h), px, i * 3 * s * s) }
        val hidden = OnnxTensor.createTensor(env, FloatBuffer.wrap(px), longArrayOf(b.toLong(), 3, s.toLong(), s.toLong())).use { t ->
            encoder.run(mapOf("pixel_values" to t)).use { r ->
                val o = r[0] as OnnxTensor
                OnnxTensor.createTensor(env, o.floatBuffer, o.info.shape)
            }
        }
        val outs = Array(b) { ArrayList<Int>() }
        val done = BooleanArray(b)
        var tokens = LongArray(b) { startId.toLong() }
        var prev: OrtSession.Result? = null; var first: OrtSession.Result? = null
        var encPast: Map<String, OnnxTensor> = emptyMap()
        val emptyShape = longArrayOf(b.toLong(), heads.toLong(), 0, headDim.toLong())
        val empty = (0 until layers).flatMap { l -> listOf("decoder.key", "decoder.value", "encoder.key", "encoder.value").map { "past_key_values.$l.$it" } }
            .associateWith { OnnxTensor.createTensor(env, FloatBuffer.allocate(0), emptyShape) }
        try {
            for (t in 0 until maxTokens) {
                if (cancel()) throw InterruptedException("cancelled")
                val past = if (prev == null) empty else HashMap<String, OnnxTensor>().also { p ->
                    for (l in 0 until layers) {
                        p["past_key_values.$l.decoder.key"] = prev!!.get("present.$l.decoder.key").get() as OnnxTensor
                        p["past_key_values.$l.decoder.value"] = prev!!.get("present.$l.decoder.value").get() as OnnxTensor
                    }
                    p.putAll(encPast)
                }
                val res = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), longArrayOf(b.toLong(), 1)).use { ti ->
                    OnnxTensor.createTensor(env, booleanArrayOf(prev != null)).use { flag ->
                        val inputs = HashMap<String, OnnxTensor>(past)
                        inputs["input_ids"] = ti; inputs["encoder_hidden_states"] = hidden
                        if ("use_cache_branch" in decInputs) inputs["use_cache_branch"] = flag
                        decoder.run(inputs)
                    }
                }
                if (prev == null) {
                    encPast = (0 until layers).flatMap { l -> listOf("past_key_values.$l.encoder.key" to res.get("present.$l.encoder.key").get() as OnnxTensor,
                        "past_key_values.$l.encoder.value" to res.get("present.$l.encoder.value").get() as OnnxTensor) }.toMap()
                    first = res
                } else if (prev !== first) prev.close()
                prev = res
                val logits = (res[0] as OnnxTensor).floatBuffer
                val v = logits.capacity() / b
                val next = LongArray(b); var allDone = true
                for (r in 0 until b) {
                    if (done[r]) { next[r] = padId.toLong(); continue }
                    var best = 0; var bv = Float.NEGATIVE_INFINITY
                    for (k in 0 until v) { if (k == padId) continue; val x = logits.get(r * v + k); if (x > bv) { bv = x; best = k } }
                    if (best == eosId) done[r] = true else outs[r].add(best)
                    next[r] = best.toLong(); if (!done[r]) allDone = false
                }
                if (allDone) break
                tokens = next
            }
        } finally {
            if (prev != null && prev !== first) prev.close()
            first?.close(); empty.values.forEach { it.close() }; hidden.close()
        }
        return outs.map { ids -> ids.joinToString("") { id -> vocab.getOrNull(id)?.takeIf { !(it.startsWith("<") && it.endsWith(">")) } ?: "" }.replace('▁', ' ').trim() }
    }

    override fun close() { encoder.close(); decoder.close() }
}
