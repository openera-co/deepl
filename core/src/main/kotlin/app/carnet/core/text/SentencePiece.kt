package app.carnet.core.text

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer

/**
 * Minimal SentencePiece unigram encoder/decoder.
 *
 * Reads the compact `.pieces` file written by tools/export_model.py (pieces, scores and types),
 * applies the same normalisation as the `nmt_nfkc` normaliser used by Opus-MT models,
 * and segments with Viterbi over piece scores, exactly like the reference implementation.
 */
class SentencePiece private constructor(
    private val pieces: Array<String>,
    private val scores: FloatArray,
    private val types: IntArray,
) {
    private val index = HashMap<String, Int>(pieces.size * 2)
    private val maxPieceLen: Int
    private val unkId: Int
    private val unkScore: Float

    init {
        var maxLen = 1
        var minScore = Float.MAX_VALUE
        var unk = 0
        for (i in pieces.indices) {
            when (types[i]) {
                TYPE_NORMAL, TYPE_USER -> {
                    index[pieces[i]] = i
                    maxLen = maxOf(maxLen, pieces[i].length)
                    minScore = minOf(minScore, scores[i])
                }
                TYPE_UNKNOWN -> unk = i
            }
        }
        maxPieceLen = maxLen
        unkId = unk
        unkScore = minScore - UNK_PENALTY
    }

    val size: Int get() = pieces.size

    fun piece(id: Int): String = pieces[id]

    /** Normalises [text] and returns the best segmentation as piece strings. */
    fun encode(text: String): List<String> {
        val norm = normalize(text)
        if (norm.isEmpty()) return emptyList()
        val n = norm.length
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val prev = IntArray(n + 1) { -1 }
        val prevId = IntArray(n + 1) { -1 }
        best[0] = 0f
        for (i in 0 until n) {
            if (best[i] == Float.NEGATIVE_INFINITY) continue
            // Never split a surrogate pair.
            if (Character.isLowSurrogate(norm[i]) && i > 0 && Character.isHighSurrogate(norm[i - 1])) continue
            var matchedSingle = false
            val limit = minOf(n, i + maxPieceLen)
            var end = i + 1
            while (end <= limit) {
                if (end < n && Character.isLowSurrogate(norm[end]) && Character.isHighSurrogate(norm[end - 1])) {
                    end++
                    continue
                }
                val id = index[norm.substring(i, end)]
                if (id != null) {
                    val s = best[i] + scores[id]
                    if (s > best[end]) {
                        best[end] = s; prev[end] = i; prevId[end] = id
                    }
                    if (end == i + charLen(norm, i)) matchedSingle = true
                }
                end++
            }
            if (!matchedSingle) {
                val end1 = i + charLen(norm, i)
                val s = best[i] + unkScore
                if (s > best[end1]) {
                    best[end1] = s; prev[end1] = i; prevId[end1] = unkId
                }
            }
        }
        val out = ArrayList<String>()
        var lastWasUnk = false
        var pos = n
        while (pos > 0) {
            val start = prev[pos]
            val id = prevId[pos]
            // Consecutive unknown characters are merged into one piece, like the reference.
            if (id == unkId && out.isNotEmpty() && lastWasUnk) {
                out[out.size - 1] = norm.substring(start, pos) + out[out.size - 1]
            } else {
                out.add(if (id == unkId) norm.substring(start, pos) else pieces[id])
            }
            lastWasUnk = id == unkId
            pos = start
        }
        out.reverse()
        return out
    }

    /** Joins target pieces back into text. */
    fun decode(pieceStrings: List<String>): String {
        val sb = StringBuilder()
        for (p in pieceStrings) sb.append(p)
        return sb.toString().replace(SPACE, ' ').trim()
    }

    companion object {
        const val TYPE_NORMAL = 0
        const val TYPE_UNKNOWN = 2
        const val TYPE_CONTROL = 3
        const val TYPE_USER = 4
        const val TYPE_UNUSED = 5
        const val TYPE_BYTE = 6
        private const val UNK_PENALTY = 10f
        const val SPACE = '▁'

        fun load(input: InputStream): SentencePiece {
            val bytes = input.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val n = buf.int
            val pieces = arrayOfNulls<String>(n)
            val scores = FloatArray(n)
            val types = IntArray(n)
            for (i in 0 until n) {
                // The exporter writes type codes from sentencepiece_model.proto, remapping NORMAL=1 to 0.
                val t = buf.int
                types[i] = if (t == 1) TYPE_NORMAL else t
                scores[i] = buf.float
                val len = buf.int
                val b = ByteArray(len)
                buf.get(b)
                pieces[i] = String(b, Charsets.UTF_8)
            }
            @Suppress("UNCHECKED_CAST")
            return SentencePiece(pieces as Array<String>, scores, types)
        }

        private fun charLen(s: String, i: Int): Int =
            if (Character.isHighSurrogate(s[i]) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) 2 else 1

        /**
         * nmt_nfkc: NFKC plus removal of control characters and mapping of exotic spaces;
         * then whitespace collapsing, trimming, dummy prefix and whitespace escaping.
         */
        fun normalize(text: String): String {
            val sb = StringBuilder(text.length + 8)
            for (ch in Normalizer.normalize(text, Normalizer.Form.NFKC)) {
                val c = ch.code
                when {
                    c == 0x09 || c == 0x0A || c == 0x0D || c == 0x20 || c == 0xA0 || c == 0x3000 ||
                        c == 0x2028 || c == 0x2029 || c == 0x1680 || (c in 0x2000..0x200A) || c == 0x202F || c == 0x205F ->
                        sb.append(' ')
                    c < 0x20 || c == 0x7F || (c in 0x80..0x9F) -> {} // drop control characters
                    c == 0x200B || c == 0x200C || c == 0x200D || c == 0xFEFF || c == 0x2060 -> {} // zero-width
                    else -> sb.append(ch)
                }
            }
            // Collapse whitespace, trim, add the dummy prefix and escape spaces.
            val out = StringBuilder(sb.length + 1)
            var lastSpace = true
            for (ch in sb) {
                if (ch == ' ') {
                    if (!lastSpace) { out.append(SPACE); lastSpace = true }
                } else {
                    if (out.isEmpty()) out.append(SPACE)
                    out.append(ch); lastSpace = false
                }
            }
            if (out.isNotEmpty() && out[out.length - 1] == SPACE) out.setLength(out.length - 1)
            return out.toString()
        }
    }
}
