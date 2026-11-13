package app.carnet.core.text

/**
 * Repairs single-letter OCR confusions ("Jundi" → "lundi", "tui" → "lui") with a word list: a
 * recognised word the list doesn't know is replaced only when exactly one look-alike variant is
 * known. [known] are whole words (the translation model's vocabulary is a good source).
 */
class OcrWords(private val known: Set<String>) {
    private val swaps = listOf("J" to "l", "I" to "l", "t" to "l", "1" to "l", "l" to "i", "rn" to "m", "m" to "rn", "cl" to "d", "vv" to "w",
        "0" to "o", "5" to "s", "f" to "t", "c" to "e", "e" to "c", "n" to "u", "u" to "n", "h" to "b", "b" to "h", "é" to "è", "è" to "é", "a" to "à")
    private val word = Regex("[\\p{L}\\d]{3,}")

    fun isWord(w: String) = isKnown(w)
    private fun isKnown(w: String) = w in known || w.lowercase() in known || w.lowercase().replaceFirstChar { it.uppercase() } in known

    fun fix(text: String): String = word.replace(text) { m ->
        val w = m.value
        if (isKnown(w) || w.any { it.isDigit() } && w.any { it.isLetter() && !it.isUpperCase() }.not()) return@replace w
        val variants = HashSet<String>()
        for ((a, b) in swaps) {
            var i = w.indexOf(a)
            while (i >= 0) { variants.add(w.substring(0, i) + b + w.substring(i + a.length)); i = w.indexOf(a, i + 1) }
        }
        val hits = variants.filter { v -> v.any { it.isLetter() } && isKnown(v) }
        if (hits.size == 1) hits[0] else w
    }

    companion object {
        /** Whole words of a SentencePiece vocabulary (pieces starting with ▁, without it). */
        fun fromVocab(lines: Sequence<String>): OcrWords =
            OcrWords(lines.filter { it.length > 3 && it[0] == '▁' && it.drop(1).all { c -> c.isLetter() || c == '\'' || c == '-' } }.map { it.drop(1) }.toHashSet())
    }
}
