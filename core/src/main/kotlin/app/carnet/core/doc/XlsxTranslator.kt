package app.carnet.core.doc

import org.w3c.dom.Document
import org.w3c.dom.Element

/** Excel workbooks: shared strings (plain and rich), inline strings, comments, charts. Formulas are never touched. */
class XlsxTranslator : ZoneDocumentTranslator() {
    companion object {
        const val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    }

    override fun collect(pkg: ZipPackage, options: TranslateOptions, sink: MutableList<ZoneJob>, stats: DocStats): () -> Unit {
        val docs = LinkedHashMap<String, Document>()
        // String literals used inside formulas (e.g. =IF(A1="Oui";…)) must keep their value.
        val formulaLiterals = HashSet<String>()
        val sheetNames = pkg.names().filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
        for (name in sheetNames) {
            val s = String(pkg[name]!!, Charsets.UTF_8)
            for (f in Regex("<f[^>]*>([^<]*)</f>").findAll(s)) {
                for (lit in Regex("&quot;(.*?)&quot;|\"(.*?)\"").findAll(f.groupValues[1])) {
                    formulaLiterals.add((lit.groupValues[1].ifEmpty { lit.groupValues[2] }))
                }
            }
        }
        // Data validation lists ("Oui,Non") also reference cell values.
        for (name in sheetNames) {
            val s = String(pkg[name]!!, Charsets.UTF_8)
            for (m in Regex("<formula1>&quot;(.*?)&quot;</formula1>").findAll(s)) m.groupValues[1].split(',').forEach { formulaLiterals.add(it.trim()) }
        }

        pkg["xl/sharedStrings.xml"]?.let { bytes ->
            val doc = Xml.parse(bytes)
            docs["xl/sharedStrings.xml"] = doc
            stats.parts++
            for (si in Xml.all(doc, S, "si")) collectStringItem(si, sink, stats, formulaLiterals)
        }
        for (name in sheetNames) {
            val bytes = pkg[name]!!
            if (!String(bytes, Charsets.UTF_8).contains("inlineStr")) continue
            val doc = Xml.parse(bytes)
            docs[name] = doc
            stats.parts++
            for (isEl in Xml.all(doc, S, "is")) collectStringItem(isEl, sink, stats, formulaLiterals)
        }
        if (options.translateComments) for (name in pkg.names().filter { it.matches(Regex("xl/comments\\d*\\.xml")) }) {
            val doc = Xml.parse(pkg[name]!!)
            docs[name] = doc
            stats.parts++
            for (t in Xml.all(doc, S, "text")) collectStringItem(t, sink, stats, emptySet())
        }
        for (name in pkg.names().filter { it.matches(Regex("xl/(charts/chart|drawings/drawing|diagrams/data)\\d*\\.xml")) }) {
            val doc = Xml.parse(pkg[name]!!)
            docs[name] = doc
            stats.parts++
            DrawingMLTranslator.collectDrawingParagraphs(doc, sink, stats)
        }
        return {
            for ((name, doc) in docs) {
                DrawingMLTranslator.setLanguage(doc, options.targetLanguage)
                pkg[name] = Xml.serialize(doc)
            }
        }
    }

    private fun collectStringItem(si: Element, sink: MutableList<ZoneJob>, stats: DocStats, protected: Set<String>) {
        stats.paragraphs++
        val direct = Xml.child(si, S, "t")
        val runs = Xml.children(si).filter { it.namespaceURI == S && it.localName == "r" }
        if (direct != null) {
            val text = direct.textContent
            if (text in protected) return
            sink.add(ZoneJob(listOf(RunText(0, text))) { regions ->
                direct.textContent = regions.joinToString("") { it.text }
                direct.setAttributeNS(DocxTranslator.XML_NS, "xml:space", "preserve")
            })
            return
        }
        if (runs.isEmpty()) return
        val texts = runs.map { Xml.child(it, S, "t")?.textContent ?: "" }
        if (texts.joinToString("") in protected) return
        val formats = ArrayList<String>()
        val rt = runs.mapIndexed { i, r ->
            val key = Xml.canonical(Xml.child(r, S, "rPr"))
            var idx = formats.indexOf(key)
            if (idx < 0) { formats.add(key); idx = formats.size - 1 }
            RunText(idx, texts[i])
        }
        val templates = formats.map { f -> runs[rt.indexOfFirst { formats[it.format] == f }] }
        sink.add(ZoneJob(rt) { regions ->
            val doc = si.ownerDocument
            for (reg in regions) {
                val r = templates[reg.format].cloneNode(true) as Element
                val t = Xml.child(r, S, "t") ?: doc.createElementNS(S, "t").also { r.appendChild(it) }
                t.textContent = reg.text
                t.setAttributeNS(DocxTranslator.XML_NS, "xml:space", "preserve")
                si.insertBefore(r, runs.first())
            }
            for (r in runs) si.removeChild(r)
        })
    }
}
