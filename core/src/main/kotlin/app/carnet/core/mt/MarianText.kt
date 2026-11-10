package app.carnet.core.mt

import app.carnet.core.text.SentencePiece

/**
 * The text side of a Marian model, shared by every engine: SentencePiece pieces in, pieces out,
 * with the same character mapping as [MarianModel] (so a native engine sees identical input).
 */
class MarianText(source: ModelSource) {
    val meta = ModelMeta.parse(String(source.open("model.json")))
    private val srcSp = SentencePiece.load(source.open("source.pieces").inputStream())
    private val tgtSp = SentencePiece.load(source.open("target.pieces").inputStream())
    private val vocab: Set<String> = String(source.open("vocab.txt"), Charsets.UTF_8)
        .split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }
        .map { it.replace("\\n", "\n") }.toHashSet()

    private val charMap: Map<Char, String> = buildMap {
        val candidates = mapOf('’' to "'", '‘' to "'", '‛' to "'", '`' to "'", '´' to "'", '“' to "\"", '”' to "\"", '„' to "\"",
            '‹' to "<", '›' to ">", '–' to "-", '—' to "-", '‑' to "-", '…' to "...", ' ' to " ", ' ' to " ", 'œ' to "oe", 'Œ' to "Oe")
        for ((c, r) in candidates) if (srcSp.encode("a${c}a").any { it !in vocab }) put(c, r)
    }

    private fun prepare(text: String): String {
        if (charMap.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(charMap[c] ?: c)
        return sb.toString()
    }

    /** Source pieces for [text], unknown ones as <unk>, ending with </s>. */
    fun encode(text: String, maxTokens: Int = 256): List<String> {
        val pieces = srcSp.encode(prepare(text))
        val out = ArrayList<String>(minOf(pieces.size, maxTokens - 1) + 1)
        for (p in pieces) { if (out.size >= maxTokens - 1) break; out.add(if (p in vocab) p else "<unk>") }
        out.add("</s>")
        return out
    }

    fun decode(pieces: List<String>): String = tgtSp.decode(pieces.filter { it != "</s>" && it != "<pad>" && it != "<unk>" })
}

/**
 * A [SentenceTranslator] over a native engine that maps pieces to pieces ([run]: batch, beam →
 * batch). Sentences are sorted by length and batched so padding stays small; [cancel] is checked
 * between batches.
 */
class PiecesTranslator(
    private val text: MarianText,
    private val beam: Int = 1,
    private val maxBatch: Int = 16,
    private val run: (List<List<String>>, Int) -> List<List<String>>,
) : SentenceTranslator {
    private fun go(sentences: List<String>, beam: Int, cancel: () -> Boolean): List<String> {
        if (sentences.isEmpty()) return emptyList()
        val enc = sentences.map { text.encode(it) }
        val order = enc.indices.sortedBy { enc[it].size }
        val out = arrayOfNulls<String>(sentences.size)
        var i = 0
        while (i < order.size) {
            if (cancel()) throw InterruptedException("cancelled")
            // Long sentences go in smaller batches (memory and padding).
            val longest = enc[order[minOf(order.size, i + maxBatch) - 1]].size
            val size = if (longest > 120) maxOf(1, maxBatch / 4) else if (longest > 60) maxOf(1, maxBatch / 2) else maxBatch
            val idx = order.subList(i, minOf(order.size, i + size))
            val res = run(idx.map { enc[it] }, beam)
            idx.forEachIndexed { k, j -> out[j] = text.decode(res[k]) }
            i += idx.size
        }
        return out.map { it ?: "" }
    }

    override fun translateSentences(sentences: List<String>, cancel: () -> Boolean) = go(sentences, beam, cancel)
    override fun translateCarefully(sentences: List<String>, cancel: () -> Boolean) = go(sentences, 4, cancel)
}
