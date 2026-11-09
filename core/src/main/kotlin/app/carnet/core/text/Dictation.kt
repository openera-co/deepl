package app.carnet.core.text

/**
 * Turns French dictation into written text: teachers say punctuation aloud
 * ("virgule", "point à la ligne", "ouvrez la parenthèse"…). Also capitalises sentence starts
 * and applies French typographic spacing.
 */
object Dictation {
    private data class Rule(val words: List<String>, val out: String)

    private val rules = listOf(
        "point à la ligne" to ".\n", "point a la ligne" to ".\n", "point final" to ".", "point virgule" to ";", "point-virgule" to ";",
        "points de suspension" to "…", "point d'interrogation" to "?", "point d'exclamation" to "!",
        "deux points" to ":", "deux-points" to ":", "virgule" to ",",
        "ouvrez les guillemets" to "«", "ouvrir les guillemets" to "«", "ouvrez guillemets" to "«", "fermez les guillemets" to "»", "fermer les guillemets" to "»", "fermez guillemets" to "»",
        "ouvrez la parenthèse" to "(", "ouvrir la parenthèse" to "(", "ouvrez parenthèse" to "(", "fermez la parenthèse" to ")", "fermer la parenthèse" to ")", "fermez parenthèse" to ")",
        "nouveau paragraphe" to "\n\n", "nouvel alinéa" to "\n\n", "retour à la ligne" to "\n", "à la ligne" to "\n", "a la ligne" to "\n", "nouvelle ligne" to "\n",
        "tiret" to "–", "trait d'union" to "-",
    ).map { (k, v) -> Rule(k.split(' '), v) }.sortedByDescending { it.words.size }

    private val notBeforePoint = setOf("le", "un", "ce", "du", "au", "de", "à", "mon", "ton", "son", "quel", "leur", "bon", "premier", "dernier",
        "même", "notre", "votre", "chaque", "seul", "second", "deuxième", "troisième", "cet", "un", "ce")

    /** Applies spoken-punctuation commands to [raw] recognised text. [endOfUtterance]: the speaker paused. */
    fun format(raw: String, enabled: Boolean = true): String {
        val words = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return ""
        val tokens = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            if (enabled) {
                val rule = rules.firstOrNull { r -> r.words.indices.all { k -> i + k < words.size && words[i + k].lowercase() == r.words[k] } }
                if (rule != null) {
                    // "à la ligne" alone only counts when it isn't part of a sentence like "aller à la ligne".
                    tokens.add("\u0000" + rule.out); i += rule.words.size; continue
                }
                val w = words[i].lowercase()
                if (w == "point") {
                    val prev = tokens.lastOrNull()?.lowercase()
                    val nextIsEnd = i == words.size - 1
                    if (prev == null || prev.startsWith("\u0000") || prev !in notBeforePoint) {
                        if (nextIsEnd || startsSentence(words.getOrNull(i + 1))) { tokens.add("\u0000."); i++; continue }
                    }
                }
            }
            tokens.add(words[i]); i++
        }
        // Assemble with French spacing: space before ; : ! ? » and after «.
        val sb = StringBuilder()
        var capNext = true
        for (t in tokens) {
            if (t.startsWith("\u0000")) {
                val p = t.substring(1)
                when (p) {
                    ",", "." , "…" -> { trimEnd(sb); sb.append(p) }
                    ";", ":", "!", "?", "»" -> { trimEnd(sb); sb.append(' ').append(p) }
                    "«" -> { if (sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' '); sb.append("« ") }
                    "(" -> { if (sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' '); sb.append('(') }
                    ")" -> { trimEnd(sb); sb.append(')') }
                    "–" -> { if (sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' '); sb.append("– ") }
                    "-" -> { trimEnd(sb); sb.append('-') }
                    else -> { trimEnd(sb); sb.append(p) }
                }
                if (p.contains('.') || p == "?" || p == "!" || p == "…" || p.contains('\n')) capNext = true
                continue
            }
            if (sb.isNotEmpty() && !sb.last().isWhitespace() && sb.last() != '(' && sb.last() != '-' && !sb.endsWith("« ")) sb.append(' ')
            sb.append(if (capNext) t.replaceFirstChar { it.uppercase() } else t)
            capNext = false
        }
        return sb.toString().replace(Regex(" +\n"), "\n").replace(Regex("\n +"), "\n")
    }

    private fun trimEnd(sb: StringBuilder) { while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1) }

    private val sentenceStarters = setOf("il", "elle", "on", "ils", "elles", "les", "le", "la", "l'", "nous", "vous", "je", "c'est", "ce", "cette",
        "en", "pour", "dans", "un", "une", "des", "ensuite", "puis", "enfin", "alors", "mais", "donc", "par", "au", "aux", "à", "d'abord", "cela", "ceci", "lorsque", "quand", "si")

    private fun startsSentence(w: String?): Boolean {
        if (w == null) return true
        val lw = w.lowercase()
        return lw in sentenceStarters || rules.any { it.words.first() == lw } || w.first().isUpperCase()
    }
}
