package app.carnet.core.doc

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.Text

/** OpenDocument text, spreadsheets and presentations (content.xml, styles.xml headers/footers). */
class OdfTranslator : ZoneDocumentTranslator() {
    companion object {
        const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
        const val FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
        const val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    }

    override fun collect(pkg: ZipPackage, options: TranslateOptions, sink: MutableList<ZoneJob>, stats: DocStats): () -> Unit {
        val docs = LinkedHashMap<String, Document>()
        for (name in listOf("content.xml", "styles.xml")) {
            val bytes = pkg[name] ?: continue
            val doc = Xml.parse(bytes)
            docs[name] = doc
            stats.parts++
            for (tag in listOf("p", "h")) for (p in Xml.all(doc, TEXT, tag)) {
                if (!options.translateComments && isInside(p, "annotation")) continue
                collectParagraph(p, sink, stats)
            }
        }
        val lang = options.targetLanguage.substringBefore('-')
        val country = options.targetLanguage.substringAfter('-', "")
        return {
            for ((name, doc) in docs) {
                for (tag in listOf("text-properties")) for (e in Xml.all(doc, STYLE, tag)) {
                    if (e.getAttributeNS(FO, "language").startsWith("fr")) {
                        e.setAttributeNS(FO, "fo:language", lang)
                        if (country.isNotEmpty()) e.setAttributeNS(FO, "fo:country", country)
                    }
                }
                pkg[name] = Xml.serialize(doc)
            }
        }
    }

    private fun isInside(e: Element, local: String): Boolean {
        var n: Node? = e.parentNode
        while (n != null) { if (n is Element && n.localName == local) return true; n = n.parentNode }
        return false
    }

    private class Item(val node: Node, val style: String, val text: String)

    private fun collectParagraph(p: Element, sink: MutableList<ZoneJob>, stats: DocStats) {
        stats.paragraphs++
        val zones = ArrayList<MutableList<Item>>()
        var cur: MutableList<Item>? = null
        fun add(item: Item) {
            if (cur == null) { cur = ArrayList(); zones.add(cur!!) }
            cur!!.add(item)
        }
        var n = p.firstChild
        while (n != null) {
            when {
                n is Text -> add(Item(n, "", n.data))
                n is Element && n.namespaceURI == TEXT && n.localName == "span" && simpleSpan(n) ->
                    add(Item(n, n.getAttributeNS(TEXT, "style-name"), spanText(n)))
                n is Element && n.namespaceURI == TEXT && n.localName == "s" -> add(Item(n, "", " ".repeat(n.getAttributeNS(TEXT, "c").toIntOrNull() ?: 1)))
                n is Element && n.namespaceURI == TEXT && n.localName == "tab" -> add(Item(n, "", "\t"))
                n is Element && n.namespaceURI == TEXT && n.localName == "line-break" -> add(Item(n, "", "\n"))
                n is Element && n.namespaceURI == TEXT && (n.localName == "soft-page-break" || n.localName.startsWith("bookmark")) -> {}
                else -> cur = null
            }
            n = n.nextSibling
        }
        for (zone in zones) {
            if (zone.all { it.text.isBlank() }) continue
            val formats = ArrayList<String>()
            val runs = zone.map { it ->
                var idx = formats.indexOf(it.style)
                if (idx < 0) { formats.add(it.style); idx = formats.size - 1 }
                RunText(idx, it.text)
            }
            sink.add(ZoneJob(runs) { regions -> rewrite(p, zone, formats, regions) })
        }
    }

    private fun simpleSpan(e: Element): Boolean {
        var c = e.firstChild
        while (c != null) {
            if (c is Element && !(c.namespaceURI == TEXT && c.localName in setOf("s", "tab", "line-break", "soft-page-break"))) return false
            c = c.nextSibling
        }
        return true
    }

    private fun spanText(e: Element): String {
        val sb = StringBuilder()
        var c = e.firstChild
        while (c != null) {
            when {
                c is Text -> sb.append(c.data)
                c is Element && c.localName == "s" -> sb.append(" ".repeat(c.getAttributeNS(TEXT, "c").toIntOrNull() ?: 1))
                c is Element && c.localName == "tab" -> sb.append('\t')
                c is Element && c.localName == "line-break" -> sb.append('\n')
            }
            c = c.nextSibling
        }
        return sb.toString()
    }

    private fun rewrite(p: Element, zone: List<Item>, formats: List<String>, regions: List<Region>) {
        val doc = p.ownerDocument
        val first = zone.first().node
        for (reg in regions) {
            val style = formats[reg.format]
            val target: Node = if (style.isEmpty()) doc.createDocumentFragment() else doc.createElementNS(TEXT, "text:span").also {
                (it as Element).setAttributeNS(TEXT, "text:style-name", style)
            }
            appendText(doc, target, reg.text)
            p.insertBefore(target, first)
        }
        for (it in zone) p.removeChild(it.node)
    }

    /** Writes text using ODF whitespace elements (consecutive spaces collapse otherwise). */
    private fun appendText(doc: Document, target: Node, text: String) {
        val buf = StringBuilder()
        fun flush() { if (buf.isNotEmpty()) { target.appendChild(doc.createTextNode(buf.toString())); buf.setLength(0) } }
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '\t' -> { flush(); target.appendChild(doc.createElementNS(TEXT, "text:tab")) }
                ch == '\n' -> { flush(); target.appendChild(doc.createElementNS(TEXT, "text:line-break")) }
                ch == ' ' && (i == 0 || text[i - 1] == ' ') -> {
                    var j = i
                    while (j < text.length && text[j] == ' ') j++
                    flush()
                    val s = doc.createElementNS(TEXT, "text:s")
                    if (j - i > 1) s.setAttributeNS(TEXT, "text:c", (j - i).toString())
                    target.appendChild(s)
                    i = j; continue
                }
                else -> buf.append(ch)
            }
            i++
        }
        flush()
    }
}
