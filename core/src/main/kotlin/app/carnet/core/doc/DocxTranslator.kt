package app.carnet.core.doc

import org.w3c.dom.Document
import org.w3c.dom.Element

/** Word documents: body, tables, text boxes, headers, footers, footnotes, endnotes, comments, charts, SmartArt. */
class DocxTranslator : ZoneDocumentTranslator() {

    companion object {
        const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        const val XML_NS = "http://www.w3.org/XML/1998/namespace"
        private val containers = setOf("hyperlink", "ins", "smartTag", "fldSimple", "sdt", "sdtContent", "customXml", "dir", "bdo", "moveTo")
        private val skipped = setOf("del", "moveFrom", "proofErr", "bookmarkStart", "bookmarkEnd", "commentRangeStart", "commentRangeEnd", "permStart", "permEnd")
        private val textChildren = setOf("rPr", "t", "tab", "br", "noBreakHyphen", "softHyphen", "cr", "lastRenderedPageBreak")
    }

    override fun collect(pkg: ZipPackage, options: TranslateOptions, sink: MutableList<ZoneJob>, stats: DocStats): () -> Unit {
        val docs = LinkedHashMap<String, Document>()
        for (name in pkg.names()) {
            val wordPart = name.startsWith("word/") && name.endsWith(".xml") && (
                name == "word/document.xml" || name.matches(Regex("word/(header|footer)\\d*\\.xml")) ||
                    (options.translateNotes && (name == "word/footnotes.xml" || name == "word/endnotes.xml")) ||
                    (options.translateComments && name == "word/comments.xml"))
            val drawingPart = name.matches(Regex("word/(charts/chart|diagrams/data|diagrams/drawing)\\d*\\.xml"))
            if (wordPart) {
                val doc = Xml.parse(pkg[name]!!)
                docs[name] = doc
                stats.parts++
                for (p in Xml.all(doc, W, "p")) collectParagraph(p, sink, stats)
            } else if (drawingPart) {
                val doc = Xml.parse(pkg[name]!!)
                docs[name] = doc
                stats.parts++
                DrawingMLTranslator.collectDrawingParagraphs(doc, sink, stats)
            }
        }
        val styles = pkg["word/styles.xml"]?.let { Xml.parse(it) }
        return {
            for ((name, doc) in docs) {
                setLanguage(doc, options.targetLanguage)
                pkg[name] = Xml.serialize(doc)
            }
            if (styles != null) { setLanguage(styles, options.targetLanguage); pkg["word/styles.xml"] = Xml.serialize(styles) }
            pkg["docProps/core.xml"]?.let { pkg["docProps/core.xml"] = setCoreLanguage(it, options.targetLanguage) }
        }
    }

    private class TextRun(val el: Element, val format: String, val text: String)

    private fun collectParagraph(p: Element, sink: MutableList<ZoneJob>, stats: DocStats) {
        stats.paragraphs++
        val zones = ArrayList<MutableList<TextRun>>()
        var cur: MutableList<TextRun>? = null
        fun walk(parent: Element) {
            for (c in Xml.children(parent)) {
                if (c.namespaceURI != W) { cur = null; continue }
                when (c.localName) {
                    "r" -> {
                        val tr = asTextRun(c)
                        if (tr == null) { cur = null; continue }
                        val z = cur
                        if (z != null && z.last().el.parentNode === c.parentNode) z.add(tr)
                        else { cur = mutableListOf(tr); zones.add(cur!!) }
                    }
                    in containers -> { cur = null; walk(c); cur = null }
                    in skipped -> {}
                    "pPr" -> {}
                    else -> cur = null
                }
            }
        }
        walk(p)
        for (zone in zones) {
            if (zone.all { it.text.isBlank() }) continue
            val formats = ArrayList<String>()
            val runs = zone.map { r ->
                var idx = formats.indexOf(r.format)
                if (idx < 0) { formats.add(r.format); idx = formats.size - 1 }
                RunText(idx, r.text)
            }
            val templates = formats.map { f -> zone.first { it.format == f }.el }
            sink.add(ZoneJob(runs) { regions -> rewrite(zone.map { it.el }, templates, regions) })
        }
    }

    /** Returns the run as text if it only holds text-like content; otherwise it is an anchor (image, field…). */
    private fun asTextRun(r: Element): TextRun? {
        val sb = StringBuilder()
        var rPr: Element? = null
        for (c in Xml.children(r)) {
            if (c.namespaceURI != W || c.localName !in textChildren) return null
            when (c.localName) {
                "rPr" -> rPr = c
                "t" -> sb.append(c.textContent)
                "tab" -> sb.append('\t')
                "br" -> { if (c.getAttributeNS(W, "type").let { it == "page" || it == "column" }) return null; sb.append('\n') }
                "cr" -> sb.append('\n')
                "noBreakHyphen" -> sb.append('‑')
                "softHyphen" -> {}
            }
        }
        val key = Xml.canonical(rPr) { it.localName == "lang" || it.localName == "noProof" || it.localName == "rtl" && false }
        return TextRun(r, key, sb.toString())
    }

    private fun rewrite(old: List<Element>, templates: List<Element>, regions: List<Region>) {
        val first = old.first()
        val parent = first.parentNode
        val doc = first.ownerDocument
        for (reg in regions) {
            val run = templates[reg.format].cloneNode(true) as Element
            for (c in Xml.children(run)) if (c.localName != "rPr") run.removeChild(c)
            var i = 0
            val text = reg.text
            val buf = StringBuilder()
            fun flush() {
                if (buf.isEmpty()) return
                val t = doc.createElementNS(W, "w:t")
                t.setAttributeNS(XML_NS, "xml:space", "preserve")
                t.textContent = buf.toString()
                run.appendChild(t); buf.setLength(0)
            }
            while (i < text.length) {
                when (val ch = text[i]) {
                    '\t' -> { flush(); run.appendChild(doc.createElementNS(W, "w:tab")) }
                    '\n' -> { flush(); run.appendChild(doc.createElementNS(W, "w:br")) }
                    '‑' -> { flush(); run.appendChild(doc.createElementNS(W, "w:noBreakHyphen")) }
                    else -> buf.append(ch)
                }
                i++
            }
            flush()
            parent.insertBefore(run, first)
        }
        for (e in old) parent.removeChild(e)
    }

    /** Switches proofing language so spell-check works on the English text. */
    private fun setLanguage(doc: Document, lang: String) {
        for (l in Xml.all(doc, W, "lang")) {
            val v = l.getAttributeNS(W, "val")
            if (v.isEmpty() || v.lowercase().startsWith("fr")) l.setAttributeNS(W, "w:val", lang)
        }
    }

    private fun setCoreLanguage(bytes: ByteArray, lang: String): ByteArray {
        val s = String(bytes, Charsets.UTF_8)
        return s.replace(Regex("<dc:language>[^<]*</dc:language>"), "<dc:language>$lang</dc:language>").toByteArray()
    }
}
