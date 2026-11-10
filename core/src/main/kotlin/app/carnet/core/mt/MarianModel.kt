package app.carnet.core.mt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.carnet.core.text.SentencePiece
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/** Loads model files by name (assets on Android, a directory on desktop). */
fun interface ModelSource {
    fun open(name: String): ByteArray

    /** A file path for [name] if available; lets ONNX Runtime load large models without a Java heap copy. */
    fun path(name: String): String? = null

    /** A direct (e.g. memory-mapped) buffer for [name]; preferred over [path] when available. */
    fun buffer(name: String): java.nio.ByteBuffer? = null
}

class DirectorySource(private val dir: File) : ModelSource {
    override fun open(name: String): ByteArray = File(dir, name).readBytes()
    override fun path(name: String): String? = File(dir, name).takeIf { it.exists() }?.absolutePath
}

data class ModelMeta(
    val eosId: Int,
    val padId: Int,
    val decoderStartId: Int,
    val unkId: Int,
    val numLayers: Int,
    val numHeads: Int,
    val dModel: Int,
    val vocabSize: Int,
    /** Language pair from the exported model id, e.g. "fr" → "en". */
    val source: String = "fr",
    val target: String = "en",
) {
    val headDim get() = dModel / numHeads

    companion object {
        /** Tiny JSON reader for the flat model.json written by the exporter. */
        fun parse(json: String): ModelMeta {
            fun int(key: String): Int = Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toInt()
                ?: error("model.json missing $key")
            val pair = Regex("\"model_id\"\\s*:\\s*\"[^\"]*-([a-z]{2,3})-([a-z]{2,3})\"").find(json)
            return ModelMeta(int("eos_id"), int("pad_id"), int("decoder_start_id"), int("unk_id"),
                int("num_layers"), int("num_heads"), int("d_model"), int("vocab_size"),
                pair?.groupValues?.get(1) ?: "fr", pair?.groupValues?.get(2) ?: "en")
        }
    }
}

/**
 * A Marian (Opus-MT) encoder–decoder exported to ONNX with a merged decoder (with/without cache).
 * Thread-safe for concurrent [translate] calls; ONNX Runtime sessions are re-entrant.
 */
class MarianModel(
    source: ModelSource,
    threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
    encoderFile: String = "encoder_model.int8.onnx",
    decoderFile: String = "decoder_model_merged.int8.onnx",
) : Closeable {
    val meta = ModelMeta.parse(String(source.open("model.json")))
    private val srcSp = SentencePiece.load(source.open("source.pieces").inputStream())
    private val tgtSp = SentencePiece.load(source.open("target.pieces").inputStream())
    private val vocab: Array<String> = String(source.open("vocab.txt"), Charsets.UTF_8)
        .split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }
        .map { it.replace("\\n", "\n") }.toTypedArray()
    private val vocabIndex = HashMap<String, Int>(vocab.size * 2).also { m -> vocab.forEachIndexed { i, p -> m[p] = i } }

    private val env = OrtEnvironment.getEnvironment()
    private val encoder: OrtSession
    private val decoder: OrtSession

    init {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        }
        fun session(name: String): OrtSession = source.buffer(name)?.let { env.createSession(it, opts) }
            ?: source.path(name)?.let { p -> cached(p, opts, threads) } ?: env.createSession(source.open(name), opts)
        encoder = session(encoderFile)
        decoder = session(decoderFile)
    }

    companion object {
        /** Saved graph-optimised copies are written one at a time, in the background. */
        private val saving = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "carnet-optcache").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

        /**
         * Opening a model spends most of its time optimising the graph (constant folding of the
         * quantised weights): 2.9 s on a desktop, ~11 s on a mid-range phone. A copy saved after
         * basic optimisation opens 2.7× faster and runs within 5% of the original (same output), so
         * it's written once in the background and used from then on. Keyed by the original's size and
         * date, so an updated model rebuilds it. Skipped when storage is short.
         */
        fun cached(path: String, opts: OrtSession.SessionOptions, threads: Int): OrtSession {
            val env = OrtEnvironment.getEnvironment()
            val f = java.io.File(path)
            val cache = java.io.File(f.parentFile, f.name.removeSuffix(".onnx") + ".opt.onnx")
            val key = java.io.File(cache.path + ".key")
            val k = "${f.length()}-${f.lastModified()}-v1"
            if (cache.exists() && runCatching { key.readText() }.getOrNull() == k)
                runCatching { return env.createSession(cache.path, opts) }.onFailure { cache.delete(); key.delete() }
            val s = env.createSession(path, opts)
            if ((f.parentFile.usableSpace - f.length() * 2) > 800L * 1024 * 1024) saving.execute {
                runCatching {
                    val tmp = java.io.File(cache.path + ".tmp")
                    OrtSession.SessionOptions().use { o ->
                        o.setIntraOpNumThreads(1); o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                        o.setOptimizedModelFilePath(tmp.path)
                        env.createSession(path, o).close()
                    }
                    if (tmp.length() > 0 && tmp.renameTo(cache)) key.writeText(k) else tmp.delete()
                }
            }
            return s
        }
    }

    private val decInputNames = decoder.inputNames
    private val pastDecNames = (0 until meta.numLayers).flatMap { l ->
        listOf("past_key_values.$l.decoder.key", "past_key_values.$l.decoder.value")
    }
    private val pastEncNames = (0 until meta.numLayers).flatMap { l ->
        listOf("past_key_values.$l.encoder.key", "past_key_values.$l.encoder.value")
    }

    /**
     * Characters the model's vocabulary lacks are mapped to equivalents it knows
     * (e.g. Word's typographic apostrophe ’ would otherwise become <unk>).
     */
    private val charMap: Map<Char, String> = buildMap {
        val candidates = mapOf('’' to "'", '‘' to "'", '‛' to "'", '`' to "'", '´' to "'", '“' to "\"", '”' to "\"", '„' to "\"",
            '‹' to "<", '›' to ">", '–' to "-", '—' to "-", '‑' to "-", '…' to "...", '\u00A0' to " ", '\u202F' to " ", 'œ' to "oe", 'Œ' to "Oe")
        for ((c, r) in candidates) {
            val known = srcSp.encode("a${c}a").none { vocabIndex[it] == null }
            if (!known) put(c, r)
        }
    }

    fun prepare(text: String): String {
        if (charMap.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(charMap[c] ?: c)
        return sb.toString()
    }

    /** Vocabulary tokens for [text] without any preparation (for parity tests). */
    fun sourceTokens(text: String): List<String> = srcSp.encode(text).map { if (vocabIndex[it] == null) "<unk>" else it }

    /** Token ids for a source sentence (pieces → shared vocab → + </s>). */
    fun encodeSource(text: String, maxTokens: Int = 256): LongArray {
        val pieces = srcSp.encode(prepare(text))
        val ids = ArrayList<Long>(pieces.size + 1)
        for (p in pieces) {
            if (ids.size >= maxTokens - 1) break
            ids.add((vocabIndex[p] ?: meta.unkId).toLong())
        }
        ids.add(meta.eosId.toLong())
        return ids.toLongArray()
    }

    fun decodeTarget(ids: IntArray): String {
        val pieces = ArrayList<String>(ids.size)
        for (id in ids) {
            if (id == meta.eosId || id == meta.padId) continue
            pieces.add(if (id == meta.unkId) "" else vocab[id])
        }
        return tgtSp.decode(pieces)
    }

    fun sourcePieces(text: String) = srcSp.encode(text)

    /**
     * Translates sentences. Inputs are batched by length; [beam] 1 = greedy (fastest).
     * [cancel] is polled between decoding steps.
     */
    fun translate(
        sentences: List<String>,
        beam: Int = 1,
        maxBatchTokens: Int = 2048,
        cancel: () -> Boolean = { false },
    ): List<String> {
        if (sentences.isEmpty()) return emptyList()
        val encoded = sentences.map { encodeSource(it) }
        val order = encoded.indices.sortedBy { encoded[it].size }
        val out = arrayOfNulls<String>(sentences.size)
        var i = 0
        while (i < order.size) {
            if (cancel()) throw InterruptedException("cancelled")
            // Grow the batch while the padded token count stays under budget.
            var j = i + 1
            while (j < order.size && (j - i + 1) * encoded[order[j]].size * max(1, beam) <= maxBatchTokens && j - i < 32) j++
            val idx = order.subList(i, j)
            val ids = idx.map { encoded[it] }
            val res = if (beam <= 1) greedy(ids, cancel) else beamSearch(ids, beam, cancel)
            idx.forEachIndexed { k, orig -> out[orig] = decodeTarget(res[k]) }
            i = j
        }
        @Suppress("UNCHECKED_CAST")
        return out.toList() as List<String>
    }

    private class Encoded(val hidden: OnnxTensor, val mask: OnnxTensor, val rows: Int, val srcLen: Int) : Closeable {
        override fun close() { hidden.close(); mask.close() }
    }

    /** Runs the encoder on [ids], repeating each row [repeat] times (for beams). */
    private fun encode(ids: List<LongArray>, repeat: Int): Encoded {
        val b = ids.size
        val s = ids.maxOf { it.size }
        val inIds = LongArray(b * s) { meta.padId.toLong() }
        val mask = LongArray(b * s)
        for (r in 0 until b) for (t in ids[r].indices) { inIds[r * s + t] = ids[r][t]; mask[r * s + t] = 1 }
        val shape = longArrayOf(b.toLong(), s.toLong())
        OnnxTensor.createTensor(env, LongBuffer.wrap(inIds), shape).use { tIds ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { tMask ->
                encoder.run(mapOf("input_ids" to tIds, "attention_mask" to tMask)).use { res ->
                    val h = (res[0] as OnnxTensor).floatBuffer
                    val d = meta.dModel
                    val rows = b * repeat
                    val hid = FloatArray(rows * s * d)
                    val m = LongArray(rows * s)
                    for (r in 0 until b) for (k in 0 until repeat) {
                        val dst = r * repeat + k
                        h.position(r * s * d); h.get(hid, dst * s * d, s * d)
                        System.arraycopy(mask, r * s, m, dst * s, s)
                    }
                    return Encoded(
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(hid), longArrayOf(rows.toLong(), s.toLong(), d.toLong())),
                        OnnxTensor.createTensor(env, LongBuffer.wrap(m), longArrayOf(rows.toLong(), s.toLong())),
                        rows, s,
                    )
                }
            }
        }
    }

    private fun emptyPast(rows: Int): Map<String, OnnxTensor> {
        val shape = longArrayOf(rows.toLong(), meta.numHeads.toLong(), 0, meta.headDim.toLong())
        return (pastDecNames + pastEncNames).associateWith { OnnxTensor.createTensor(env, FloatBuffer.allocate(0), shape) }
    }

    private fun boolTensor(v: Boolean) = OnnxTensor.createTensor(env, booleanArrayOf(v))

    /** Runs one decoder step. Returns the session result (caller closes). */
    private fun step(enc: Encoded, tokens: LongArray, past: Map<String, OnnxTensor>, useCache: Boolean): OrtSession.Result {
        OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), longArrayOf(tokens.size.toLong(), 1)).use { tIn ->
            boolTensor(useCache).use { flag ->
                val inputs = HashMap<String, OnnxTensor>(past.size + 4)
                inputs["input_ids"] = tIn
                inputs["encoder_hidden_states"] = enc.hidden
                inputs["encoder_attention_mask"] = enc.mask
                if ("use_cache_branch" in decInputNames) inputs["use_cache_branch"] = flag
                inputs.putAll(past)
                return decoder.run(inputs)
            }
        }
    }

    private fun greedy(ids: List<LongArray>, cancel: () -> Boolean): List<IntArray> {
        val b = ids.size
        val maxLen = minOf(512, ids.maxOf { it.size } * 2 + 10)
        encode(ids, 1).use { enc ->
            val outs = Array(b) { ArrayList<Int>() }
            val done = BooleanArray(b)
            var tokens = LongArray(b) { meta.decoderStartId.toLong() }
            var empty: Map<String, OnnxTensor>? = emptyPast(b)
            var prev: OrtSession.Result? = null
            var encPast: Map<String, OnnxTensor> = emptyMap()
            var firstResult: OrtSession.Result? = null
            try {
                for (t in 0 until maxLen) {
                    if (cancel()) throw InterruptedException("cancelled")
                    val past: Map<String, OnnxTensor> = if (prev == null) empty!! else {
                        val p = HashMap<String, OnnxTensor>()
                        for (l in 0 until meta.numLayers) {
                            p["past_key_values.$l.decoder.key"] = prev.get("present.$l.decoder.key").get() as OnnxTensor
                            p["past_key_values.$l.decoder.value"] = prev.get("present.$l.decoder.value").get() as OnnxTensor
                        }
                        p.putAll(encPast); p
                    }
                    val res = step(enc, tokens, past, prev != null)
                    if (prev == null) {
                        // Keep the cross-attention cache from the first step for the whole decode.
                        encPast = (0 until meta.numLayers).flatMap { l ->
                            listOf("past_key_values.$l.encoder.key" to res.get("present.$l.encoder.key").get() as OnnxTensor,
                                "past_key_values.$l.encoder.value" to res.get("present.$l.encoder.value").get() as OnnxTensor)
                        }.toMap()
                        empty!!.values.forEach { it.close() }; empty = null
                        firstResult = res
                    } else if (prev !== firstResult) prev.close()
                    prev = res
                    val logits = (res[0] as OnnxTensor).floatBuffer
                    val v = meta.vocabSize
                    val next = LongArray(b)
                    var allDone = true
                    for (r in 0 until b) {
                        if (done[r]) { next[r] = meta.padId.toLong(); continue }
                        var bestId = 0; var best = Float.NEGATIVE_INFINITY
                        val base = r * v
                        for (k in 0 until v) {
                            if (k == meta.padId) continue
                            val x = logits.get(base + k)
                            if (x > best) { best = x; bestId = k }
                        }
                        if (bestId == meta.eosId) done[r] = true else outs[r].add(bestId)
                        next[r] = bestId.toLong()
                        if (!done[r]) allDone = false
                    }
                    if (allDone) break
                    tokens = next
                }
            } finally {
                if (prev != null && prev !== firstResult) prev.close()
                firstResult?.close()
                empty?.values?.forEach { it.close() }
            }
            return outs.map { it.toIntArray() }
        }
    }

    private class Hyp(val tokens: IntArray, val score: Float)

    private fun beamSearch(ids: List<LongArray>, k: Int, cancel: () -> Boolean): List<IntArray> {
        val b = ids.size
        val rows = b * k
        val maxLen = minOf(512, ids.maxOf { it.size } * 2 + 10)
        val v = meta.vocabSize
        val h = meta.numHeads; val dh = meta.headDim
        encode(ids, k).use { enc ->
            val beamScores = FloatArray(rows) { if (it % k == 0) 0f else Float.NEGATIVE_INFINITY }
            var beamTokens = Array(rows) { IntArray(0) }
            val finished = Array(b) { ArrayList<Hyp>() }
            val itemDone = BooleanArray(b)
            var tokens = LongArray(rows) { meta.decoderStartId.toLong() }
            var past: Map<String, OnnxTensor> = emptyPast(rows)
            var encPastOwner: OrtSession.Result? = null
            var encPast: Map<String, OnnxTensor> = emptyMap()
            var decPast: Map<String, OnnxTensor> = emptyMap()
            try {
                for (t in 0 until maxLen) {
                    if (cancel()) throw InterruptedException("cancelled")
                    val res = step(enc, tokens, if (t == 0) past else decPast + encPast, t > 0)
                    if (t == 0) {
                        past.values.forEach { it.close() }
                        encPastOwner = res
                        encPast = (0 until meta.numLayers).flatMap { l ->
                            listOf("past_key_values.$l.encoder.key" to res.get("present.$l.encoder.key").get() as OnnxTensor,
                                "past_key_values.$l.encoder.value" to res.get("present.$l.encoder.value").get() as OnnxTensor)
                        }.toMap()
                    }
                    val logits = (res[0] as OnnxTensor).floatBuffer
                    // Candidate selection per item.
                    val srcRow = IntArray(rows)
                    val newTok = IntArray(rows)
                    val newScore = FloatArray(rows) { Float.NEGATIVE_INFINITY }
                    val curLen = t + 1
                    val lp = FloatArray(v)
                    for (item in 0 until b) {
                        if (itemDone[item]) {
                            for (j in 0 until k) { val r = item * k + j; srcRow[r] = r; newTok[r] = meta.padId }
                            continue
                        }
                        // Top 2k over all beams of this item.
                        val candScore = FloatArray(2 * k) { Float.NEGATIVE_INFINITY }
                        val candRow = IntArray(2 * k); val candTok = IntArray(2 * k)
                        for (j in 0 until k) {
                            val r = item * k + j
                            if (beamScores[r] == Float.NEGATIVE_INFINITY) continue
                            // log-softmax of this row
                            var mx = Float.NEGATIVE_INFINITY
                            val base = r * v
                            for (x in 0 until v) { val y = logits.get(base + x); lp[x] = y; if (y > mx) mx = y }
                            var sum = 0.0
                            for (x in 0 until v) sum += exp((lp[x] - mx).toDouble())
                            val lse = mx + ln(sum).toFloat()
                            for (x in 0 until v) {
                                if (x == meta.padId) continue
                                val sc = beamScores[r] + lp[x] - lse
                                if (sc > candScore[2 * k - 1]) {
                                    var pos = 2 * k - 1
                                    while (pos > 0 && candScore[pos - 1] < sc) {
                                        candScore[pos] = candScore[pos - 1]; candRow[pos] = candRow[pos - 1]; candTok[pos] = candTok[pos - 1]; pos--
                                    }
                                    candScore[pos] = sc; candRow[pos] = r; candTok[pos] = x
                                }
                            }
                        }
                        var filled = 0
                        for (c in 0 until 2 * k) {
                            if (candScore[c] == Float.NEGATIVE_INFINITY) break
                            if (candTok[c] == meta.eosId) {
                                if (c >= k) continue // only top-k eos count, like HF
                                finished[item].add(Hyp(beamTokens[candRow[c]], candScore[c] / curLen))
                            } else {
                                val r = item * k + filled
                                srcRow[r] = candRow[c]; newTok[r] = candTok[c]; newScore[r] = candScore[c]
                                filled++
                                if (filled == k) break
                            }
                        }
                        for (j in filled until k) { val r = item * k + j; srcRow[r] = item * k; newTok[r] = meta.padId }
                        // Done when k finished and no live beam can beat the worst finished one.
                        val fin = finished[item]
                        if (fin.size >= k) {
                            fin.sortByDescending { it.score }
                            while (fin.size > k) fin.removeAt(fin.size - 1)
                            val bestLive = newScore[item * k] / curLen
                            if (fin.last().score >= bestLive) itemDone[item] = true
                        }
                    }
                    // Reorder decoder cache by source rows.
                    val newDec = HashMap<String, OnnxTensor>()
                    for (l in 0 until meta.numLayers) for (kv in arrayOf("key", "value")) {
                        val src = (res.get("present.$l.decoder.$kv").get() as OnnxTensor)
                        val fb = src.floatBuffer
                        val per = h * curLen * dh
                        val dst = FloatArray(rows * per)
                        for (r in 0 until rows) { fb.position(srcRow[r] * per); fb.get(dst, r * per, per) }
                        newDec["past_key_values.$l.decoder.$kv"] =
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(dst), longArrayOf(rows.toLong(), h.toLong(), curLen.toLong(), dh.toLong()))
                    }
                    decPast.values.forEach { it.close() }
                    decPast = newDec
                    if (res !== encPastOwner) res.close()
                    beamTokens = Array(rows) { r -> beamTokens[srcRow[r]] + newTok[r] }
                    for (r in 0 until rows) beamScores[r] = newScore[r]
                    tokens = LongArray(rows) { newTok[it].toLong() }
                    if (itemDone.all { it }) break
                }
            } finally {
                decPast.values.forEach { it.close() }
                encPastOwner?.close()
            }
            return (0 until b).map { item ->
                val fin = finished[item]
                if (fin.isEmpty()) {
                    // Fall back to the best live beam.
                    beamTokens[item * k].filter { it != meta.padId }.toIntArray()
                } else fin.maxBy { it.score }.tokens
            }
        }
    }

    override fun close() {
        encoder.close(); decoder.close()
    }
}
