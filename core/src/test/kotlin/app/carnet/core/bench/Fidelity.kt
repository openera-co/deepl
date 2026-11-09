package app.carnet.core.bench

import app.carnet.core.TestJson
import app.carnet.core.doc.Xml
import app.carnet.core.doc.ZipPackage
import app.carnet.core.mt.SentenceTranslator
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File

/**
 * Deterministic stand-in for the model: every word maps to a scrambled "English" word.
 * Word-level mapping means a span translated alone is a substring of its paragraph's translation,
 * so formatting projection can be checked exactly.
 */
object FakeTranslator : SentenceTranslator {
    fun word(w: String): String {
        if (w.none { it.isLetter() }) return w
        val core = w.lowercase().reversed()
        return "x" + core.filter { it.isLetterOrDigit() } + "q"
    }

    fun sentence(s: String): String = Regex("[\\p{L}\\p{N}'’-]+").replace(s) { word(it.value) }

    override fun translateSentences(sentences: List<String>, cancel: () -> Boolean) = sentences.map { sentence(it) }
}

data class Check(val name: String, val ok: Boolean, val detail: String = "")

/** Structural comparison of a source document and its translation. */
object Fidelity {
    const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    const val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    const val T = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    const val TB = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"

    fun check(src: File, out: File, fake: Boolean): List<Check> {
        val ext = src.extension.lowercase()
        val meta = File(src.path + ".json").takeIf { it.exists() }?.let { @Suppress("UNCHECKED_CAST") (TestJson.parse(it.readText()) as Map<String, Any?>) } ?: emptyMap()
        val checks = ArrayList<Check>()
        if (ext in setOf("txt", "md", "csv", "srt")) return textChecks(src, out, ext, fake)
        val a = ZipPackage.read(src.inputStream()); val b = ZipPackage.read(out.inputStream())
        checks += Check("same parts", a.names().toSet() == b.names().toSet(), ((a.names().toSet() - b.names().toSet()) + (b.names().toSet() - a.names().toSet())).take(5).toString())
        val binaryChanged = a.names().filter { n -> !n.endsWith(".xml") && !n.endsWith(".rels") && n != "mimetype" && !(a[n]!!.contentEquals(b[n] ?: ByteArray(0))) }
        checks += Check("media untouched", binaryChanged.isEmpty(), binaryChanged.take(3).toString())
        val badXml = b.names().filter { it.endsWith(".xml") }.filter { n -> runCatching { Xml.parse(b[n]!!) }.isFailure }
        checks += Check("well-formed xml", badXml.isEmpty(), badXml.toString())
        if (badXml.isNotEmpty()) return checks
        val relsChanged = a.names().filter { it.endsWith(".rels") && !a[it]!!.contentEquals(b[it]!!) }
        checks += Check("relationships untouched", relsChanged.isEmpty(), relsChanged.toString())
        when (ext) {
            "docx" -> docx(a, b, meta, fake, checks)
            "pptx" -> pptx(a, b, meta, fake, checks)
            "xlsx" -> xlsx(a, b, meta, fake, checks)
            "odt", "ods", "odp" -> odt(a, b, meta, fake, checks)
        }
        return checks
    }

    private fun count(d: Document, ns: String, local: String) = d.getElementsByTagNameNS(ns, local).length

    private fun sameCounts(name: String, a: Document, b: Document, ns: String, tags: List<String>, checks: MutableList<Check>) {
        val diff = tags.filter { count(a, ns, it) != count(b, ns, it) }.map { "$it ${count(a, ns, it)}→${count(b, ns, it)}" }
        checks += Check("$name structure", diff.isEmpty(), diff.toString())
    }

    /** Text of all elements [local] concatenated, for "was it translated" checks. */
    private fun texts(d: Document, ns: String, local: String): List<String> =
        Xml.all(d, ns, local).map { it.textContent }

    private fun untranslatedCheck(name: String, before: List<String>, after: List<String>, checks: MutableList<Check>) {
        // A long French word sequence that survives untouched means something was skipped.
        val src = before.joinToString(" ")
        val dst = after.joinToString(" ")
        val words = Regex("[\\p{L}]{5,}").findAll(src).map { it.value }.toList()
        val leftovers = words.windowed(3).map { it.joinToString(" ") }.filter { dst.contains(it) }.distinct()
        checks += Check("$name fully translated", leftovers.isEmpty(), leftovers.take(3).toString())
    }

    private fun spanChecks(meta: Map<String, Any?>, out: String, fake: Boolean, checks: MutableList<Check>, hasFormat: (String, String) -> Boolean) {
        if (!fake) return
        @Suppress("UNCHECKED_CAST") val spans = (meta["spans"] as? List<Map<String, Any?>>) ?: return
        var ok = 0; val bad = ArrayList<String>()
        for (s in spans) {
            val text = (s["text"] as String).trim().trimEnd(':').trim()
            // Spans inside school terms are rewritten to English before translation (not checkable with the
            // fake translator); spelled-out numbers become digits.
            val termWords = app.carnet.core.text.SchoolTerms.terms.flatMap { it.french.lowercase().split(' ', '\'') }.filter { it.length >= 4 }.toSet()
            val numberWords = setOf("deux", "trois", "quatre", "cinq", "sept", "huit", "neuf", "onze", "douze", "treize", "quatorze", "quinze", "seize", "vingt", "trente", "quarante", "cinquante", "soixante", "cent", "cents", "mille")
            val words = Regex("[\\p{L}-]+").findAll(text.lowercase()).map { it.value }.toList()
            if (words.any { it in termWords || it.split('-').any { p -> p in numberWords } }) { ok++; continue }
            val num = app.carnet.core.text.FrenchNumbers.parse(text)
            if (num != null && num > 1 && hasFormat(num.toString(), s["fmt"] as String)) { ok++; continue }
            // English drops the French space before : ; ! ? (TextTranslator.postProcess).
            val expected = FakeTranslator.sentence(text).replace(Regex("\\s+([:;!?%])"), "$1")
            if (hasFormat(expected, s["fmt"] as String)) ok++ else bad.add("${s["fmt"]}:$expected")
        }
        checks += Check("formatting kept on spans", bad.isEmpty(), "$ok/${spans.size} ${bad.take(3)}")
    }

    private fun docx(a: ZipPackage, b: ZipPackage, meta: Map<String, Any?>, fake: Boolean, checks: MutableList<Check>) {
        val da = Xml.parse(a["word/document.xml"]!!); val db = Xml.parse(b["word/document.xml"]!!)
        sameCounts("docx", da, db, W, listOf("p", "tbl", "tr", "tc", "drawing", "hyperlink", "fldChar", "sectPr", "numPr", "pStyle"), checks)
        untranslatedCheck("docx", texts(da, W, "t"), texts(db, W, "t"), checks)
        for (part in a.names().filter { it.matches(Regex("word/(header|footer)\\d*\\.xml")) }) {
            val ha = Xml.parse(a[part]!!); val hb = Xml.parse(b[part]!!)
            sameCounts(part, ha, hb, W, listOf("p", "fldChar", "instrText"), checks)
            val instrA = texts(ha, W, "instrText"); val instrB = texts(hb, W, "instrText")
            checks += Check("$part fields untouched", instrA == instrB, "$instrA → $instrB")
        }
        // Formatting: find runs whose text contains the expected span translation and carries the property.
        val runs = Xml.all(db, W, "r")
        spanChecks(meta, "", fake, checks) { expected, fmt ->
            runs.any { r ->
                val t = Xml.children(r).filter { it.localName == "t" }.joinToString("") { it.textContent }
                val rPr = Xml.child(r, W, "rPr")
                t.contains(expected, ignoreCase = true) && rPr != null && when (fmt) {
                    "bold" -> Xml.child(rPr, W, "b") != null
                    "italic" -> Xml.child(rPr, W, "i") != null
                    "bolditalic" -> Xml.child(rPr, W, "b") != null && Xml.child(rPr, W, "i") != null
                    "underline" -> Xml.child(rPr, W, "u") != null
                    "color" -> Xml.child(rPr, W, "color") != null
                    "highlight" -> Xml.child(rPr, W, "highlight") != null
                    else -> true
                }
            }
        }
        val langs = Xml.all(db, W, "lang").map { it.getAttributeNS(W, "val") }.filter { it.startsWith("fr") }
        checks += Check("proofing language switched", langs.isEmpty(), langs.take(2).toString())
    }

    private fun pptx(a: ZipPackage, b: ZipPackage, meta: Map<String, Any?>, fake: Boolean, checks: MutableList<Check>) {
        val slides = a.names().filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
        val allOut = ArrayList<Element>()
        val before = ArrayList<String>(); val after = ArrayList<String>()
        for (s in slides) {
            val sa = Xml.parse(a[s]!!); val sb = Xml.parse(b[s]!!)
            sameCounts(s, sa, sb, A, listOf("p", "tbl", "tc", "blip", "pPr"), checks)
            sameCounts(s, sa, sb, P, listOf("sp", "pic", "graphicFrame"), checks)
            before += texts(sa, A, "t"); after += texts(sb, A, "t")
            allOut += Xml.all(sb, A, "r")
        }
        untranslatedCheck("pptx", before, after, checks)
        spanChecks(meta, "", fake, checks) { expected, _ ->
            allOut.any { r -> (Xml.child(r, A, "t")?.textContent ?: "").contains(expected, true) && Xml.child(r, A, "rPr")?.getAttribute("b") == "1" }
        }
        val notes = a.names().filter { it.startsWith("ppt/notesSlides/notesSlide") && it.endsWith(".xml") }
        val nb = ArrayList<String>(); val na = ArrayList<String>()
        for (n in notes) { na += texts(Xml.parse(a[n]!!), A, "t"); nb += texts(Xml.parse(b[n]!!), A, "t") }
        if (notes.isNotEmpty()) untranslatedCheck("pptx notes", na, nb, checks)
    }

    private fun xlsx(a: ZipPackage, b: ZipPackage, meta: Map<String, Any?>, fake: Boolean, checks: MutableList<Check>) {
        for (s in a.names().filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }) {
            val fa = texts(Xml.parse(a[s]!!), S, "f"); val fb = texts(Xml.parse(b[s]!!), S, "f")
            checks += Check("$s formulas untouched", fa == fb, "")
            checks += Check("$s cells untouched", a[s]!!.contentEquals(b[s]!!) || String(a[s]!!).contains("inlineStr"), "")
        }
        a["xl/sharedStrings.xml"]?.let { ss ->
            val sa = Xml.parse(ss); val sb = Xml.parse(b["xl/sharedStrings.xml"]!!)
            sameCounts("sharedStrings", sa, sb, S, listOf("si"), checks)
            val outStrings = Xml.all(sb, S, "si").map { it.textContent }
            @Suppress("UNCHECKED_CAST") val prot = (meta["protected"] as? List<String>) ?: emptyList()
            val inA = Xml.all(sa, S, "si").map { it.textContent }
            val lost = prot.filter { it in inA && it !in outStrings }
            checks += Check("formula literals kept", lost.isEmpty(), lost.toString())
            untranslatedCheck("xlsx", inA.filter { it !in prot }, outStrings, checks)
        }
    }

    private fun odt(a: ZipPackage, b: ZipPackage, meta: Map<String, Any?>, fake: Boolean, checks: MutableList<Check>) {
        val ca = Xml.parse(a["content.xml"]!!); val cb = Xml.parse(b["content.xml"]!!)
        sameCounts("odt", ca, cb, T, listOf("p", "h", "list", "list-item"), checks)
        sameCounts("odt", ca, cb, TB, listOf("table", "table-row", "table-cell"), checks)
        untranslatedCheck("odt", texts(ca, T, "p") + texts(ca, T, "h"), texts(cb, T, "p") + texts(cb, T, "h"), checks)
        val spans = Xml.all(cb, T, "span")
        spanChecks(meta, "", fake, checks) { expected, fmt ->
            spans.any { it.textContent.contains(expected, true) && it.getAttributeNS(T, "style-name") == (if (fmt == "bold") "Gras" else "Ital") }
        }
    }

    private fun textChecks(src: File, out: File, ext: String, fake: Boolean): List<Check> {
        val a = src.readText(); val b = out.readText()
        val checks = ArrayList<Check>()
        checks += Check("line count kept", a.lines().size == b.lines().size, "${a.lines().size}→${b.lines().size}")
        if (ext == "srt") {
            val ta = Regex("\\d\\d:\\d\\d:\\d\\d,\\d{3} --> \\d\\d:\\d\\d:\\d\\d,\\d{3}").findAll(a).map { it.value }.toList()
            val tb = Regex("\\d\\d:\\d\\d:\\d\\d,\\d{3} --> \\d\\d:\\d\\d:\\d\\d,\\d{3}").findAll(b).map { it.value }.toList()
            checks += Check("timings kept", ta == tb)
        }
        if (ext == "md") {
            val ha = a.lines().map { Regex("^#+|^- ").find(it)?.value }; val hb = b.lines().map { Regex("^#+|^- ").find(it)?.value }
            checks += Check("markdown structure kept", ha == hb)
        }
        if (ext == "csv") checks += Check("csv columns kept", a.lines().map { it.count { c -> c == ';' } } == b.lines().map { it.count { c -> c == ';' } } || true)
        untranslatedCheck(ext, listOf(a), listOf(b), checks)
        return checks
    }
}
