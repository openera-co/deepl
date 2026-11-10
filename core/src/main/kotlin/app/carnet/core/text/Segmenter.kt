package app.carnet.core.text

/**
 * Splits French text into sentence-sized segments for the translation model while keeping every
 * character, so that `segments.joinToString("") { it.text }` == input.
 */
object Segmenter {
    data class Segment(val text: String, val translatable: Boolean)

    // Common French abbreviations that end with a period but do not end a sentence.
    private val abbreviations = setOf(
        "m", "mm", "mme", "mmes", "mlle", "mlles", "dr", "pr", "me", "mgr", "st", "ste", "sts",
        "av", "apr", "j.-c", "j-c", "env", "etc", "cf", "p", "pp", "ex", "vol", "chap", "fig", "éd", "ed",
        "n", "no", "nº", "tél", "tel", "art", "al", "coll", "ibid", "op", "cit", "c.-à-d", "c-à-d", "càd",
        "min", "max", "km", "kg", "cm", "env", "approx", "dép", "bd", "boul", "ch", "r", "réf", "ref", "sq", "suiv",
        "janv", "févr", "fév", "avr", "juil", "sept", "oct", "nov", "déc", "lun", "mar", "mer", "jeu", "ven", "sam", "dim",
        "ss", "s", "t", "v", "vs", "éq", "eq", "ms", "mss", "anc", "hab", "dir", "trad", "prof", "inc", "ltd", "cie",
    )

    /** Splits [text] into segments; whitespace, numbering and non-linguistic runs are marked untranslatable. */
    fun split(text: String): List<Segment> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Segment>()
        // First keep hard line breaks as separators.
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' || c == '\r' || c == '\u000B' || c == ' ') {
                if (i > start) splitLine(text.substring(start, i), out)
                var j = i
                while (j < text.length && (text[j] == '\n' || text[j] == '\r' || text[j] == '\u000B' || text[j] == ' ')) j++
                out.add(Segment(text.substring(i, j), false))
                start = j; i = j
            } else i++
        }
        if (start < text.length) splitLine(text.substring(start), out)
        return merge(out)
    }

    private fun isBreak(c: Char) = c == '\n' || c == '\r' || c == '\t' || c == '\u000B' || c == '\u2028' || c == '\u000C'

    /** Dot leaders and fill-in blanks ("Nom : ........", "____") and wide gaps are kept verbatim. */
    private val leader = Regex("[.…_·]{4,}|(?:\\. ){3,}\\.?|\t+| {3,}")

    private fun splitLine(line: String, out: MutableList<Segment>) {
        var p = 0
        for (m in leader.findAll(line)) {
            if (m.range.first > p) splitPart(line.substring(p, m.range.first), out)
            out.add(Segment(m.value, false)); p = m.range.last + 1
        }
        if (p == 0) splitPart(line, out) else if (p < line.length) splitPart(line.substring(p), out)
    }

    private val word = Regex("\\p{L}[\\p{L}'’-]*\\p{L}")
    private const val MATH = "=+×÷/<>≤≥^_∑Σ√∫·→≠≈±∈∀∃∞"
    private val unitSlash = Regex("(?<=[\\p{L}\\d)])/(?=[\\p{L}\\d(])")

    /** A formula with a few words around it ("Moyenne = 25 / 5 = 5 jours", "x̄ = Σ xᵢ pour i = 1 … N"). */
    fun isFormulaLine(t: String): Boolean {
        // A slash inside a unit ("mL/h", "km/h", "m/s²") or a date isn't maths.
        val u = t.replace(unitSlash, "")
        if (u.none { it in MATH }) return false
        val words = word.findAll(t).map { it.value }.filter { it.length >= 3 }.toList()
        if (words.isEmpty() || words.size > 3) return false
        val nonSpace = t.count { !it.isWhitespace() }
        return words.sumOf { it.length } < nonSpace * 0.5
    }

    private fun splitPart(line: String, out: MutableList<Segment>) {
        if (isFormulaLine(line)) {
            // Only the words are translated; every symbol stays exactly where it is.
            var p = 0
            for (m in word.findAll(line)) {
                if (m.value.length < 3) continue
                if (m.range.first > p) out.add(Segment(line.substring(p, m.range.first), false))
                out.add(Segment(m.value, true)); p = m.range.last + 1
            }
            if (p < line.length) out.add(Segment(line.substring(p), false))
            return
        }
        // Leading whitespace and list markers ("1.", "a)", "•", "–") stay untouched.
        val lead = Regex("^(\\s*(?:(?:[•●◦▪■–—\\-*·]|\\(?\\d{1,3}[.)]|\\(?[a-z][.)]|[A-Z]\\)|[IVXLC]{2,5}[.)]|[IVX]\\))\\s+)?)").find(line)
        var pos = 0
        if (lead != null && lead.value.isNotEmpty() && lead.value.length < line.length) {
            val marker = lead.value
            // Only treat "a." / "1." as a marker if followed by whitespace.
            out.add(Segment(marker, false)); pos = marker.length
        }
        val body = line.substring(pos)
        val trailWs = body.length - body.trimEnd().length
        val core = body.substring(0, body.length - trailWs)
        var s = 0
        var k = 0
        while (k < core.length) {
            val c = core[k]
            if (c == '.' || c == '!' || c == '?' || c == '…' || c == ';' && false) {
                var e = k + 1
                while (e < core.length && (core[e] == '.' || core[e] == '!' || core[e] == '?' || core[e] == '…')) e++
                // Closing quotes/brackets belong to the sentence.
                while (e < core.length && (core[e] == '»' || core[e] == '"' || core[e] == ')' || core[e] == '”' || core[e] == '’' || core[e] == '\'' || core[e] == ' ' && e + 1 < core.length && core[e + 1] == '»')) e++
                if (e >= core.length) break
                if (!core[e].isWhitespace()) { k = e; continue }
                if (c == '.' && isAbbreviation(core, k)) { k = e; continue }
                // Next sentence should start with an uppercase letter, digit, quote or dash.
                var n = e
                while (n < core.length && core[n].isWhitespace()) n++
                if (n < core.length && !(core[n].isUpperCase() || core[n].isDigit() || core[n] in "«\"“—–(¿¡'")) { k = e; continue }
                addSentence(core.substring(s, e), out)
                out.add(Segment(core.substring(e, n), false))
                s = n; k = n
                continue
            }
            k++
        }
        if (s < core.length) addSentence(core.substring(s), out)
        if (trailWs > 0) out.add(Segment(body.substring(body.length - trailWs), false))
    }

    private fun isAbbreviation(s: String, dot: Int): Boolean {
        var b = dot
        while (b > 0 && (s[b - 1].isLetter() || s[b - 1] == '.' || s[b - 1] == '-')) b--
        val word = s.substring(b, dot).lowercase()
        if (word.isEmpty()) return s.substring(0, dot).trimEnd().lastOrNull()?.isDigit() == true && dot + 1 < s.length && s[dot + 1].isWhitespace() && false
        if (word in abbreviations) return true
        // Single capital letter initials: "J. Dupont".
        if (word.length == 1 && s[b].isUpperCase()) return true
        // Dotted acronyms: "U.S.A."
        if (word.contains('.') && word.replace(".", "").length <= 4) return true
        return false
    }

    private fun addSentence(sentence: String, out: MutableList<Segment>) {
        if (sentence.isEmpty()) return
        out.add(Segment(sentence, isLinguistic(sentence)))
    }

    /** True if the text contains words worth translating (not just numbers, symbols, formulas, URLs). */
    fun isLinguistic(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        if (urlOrEmail.matches(t)) return false
        var letters = 0
        var maxWord = 0
        var cur = 0
        for (ch in t) {
            if (ch.isLetter()) { letters++; cur++; if (cur > maxWord) maxWord = cur } else cur = 0
        }
        if (letters == 0 || maxWord <= 1) return false
        // LaTeX-style formulas ("$\\bar{x} = \\frac{1}{N} \\sum x_i$"): no real word outside commands and braces.
        if (t.any { it == '\\' || it == '{' || it == '$' }) {
            var u = t.replace(latexCommand, " ")
            repeat(3) { u = u.replace(braced, " ") }
            if (u.split(Regex("[^\\p{L}]+")).none { it.length >= 4 }) return false
        }
        // Formulas like "v = d / t" or "CH₄ + 2 O₂": no word longer than 2 letters and math symbols present.
        if (maxWord <= 2 && t.any { it in "=+×÷/<>≤≥^_∑√∫" }) return false
        // Chemical formulas / codes like "H2O", "CO₂", "A4"
        if (maxWord <= 3 && !t.contains(' ') && t.any { it.isDigit() || it in "₀₁₂₃₄₅₆₇₈₉" }) return false
        return true
    }

    private val latexCommand = Regex("\\\\[a-zA-Z]+|[a-zA-Z]{1,4}(?=\\{)")
    private val braced = Regex("\\{[^{}]*\\}")
    private val urlOrEmail = Regex("""(?i)^(https?://\S+|www\.\S+|[\w.+-]+@[\w-]+\.[\w.]+)$""")

    private fun merge(segs: List<Segment>): List<Segment> {
        val out = ArrayList<Segment>(segs.size)
        for (s in segs) {
            val last = out.lastOrNull()
            if (last != null && !last.translatable && !s.translatable) out[out.size - 1] = Segment(last.text + s.text, false)
            else out.add(s)
        }
        return out
    }
}
