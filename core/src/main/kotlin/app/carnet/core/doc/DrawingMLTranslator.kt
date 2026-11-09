package app.carnet.core.doc

import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * DrawingML text (a:p / a:r / a:t): PowerPoint slides, layouts, masters, notes, and charts/SmartArt
 * embedded in any Office document.
 */
class DrawingMLTranslator(private val kind: Kind) : ZoneDocumentTranslator() {
    enum class Kind { PPTX }

    companion object {
        const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"

        fun collectDrawingParagraphs(doc: Document, sink: MutableList<ZoneJob>, stats: DocStats) {
            for (p in Xml.all(doc, A, "p")) collectParagraph(p, sink, stats)
        }

        private fun collectParagraph(p: Element, sink: MutableList<ZoneJob>, stats: DocStats) {
            stats.paragraphs++
            val zones = ArrayList<MutableList<Element>>()
            var cur: MutableList<Element>? = null
            for (c in Xml.children(p)) {
                if (c.namespaceURI == A && c.localName == "r") {
                    if (cur == null) { cur = ArrayList(); zones.add(cur) }
                    cur.add(c)
                } else if (c.namespaceURI == A && (c.localName == "pPr" || c.localName == "endParaRPr")) {
                    // ignore
                } else cur = null // a:br, a:fld… split zones
            }
            for (zone in zones) {
                val texts = zone.map { r -> Xml.child(r, A, "t")?.textContent ?: "" }
                if (texts.all { it.isBlank() }) continue
                val formats = ArrayList<String>()
                val runs = zone.mapIndexed { i, r ->
                    val key = Xml.canonical(Xml.child(r, A, "rPr")) { false }.replace(Regex("""lang=[^ >]+"""), "").replace(Regex("""dirty=[^ >]+|err=[^ >]+"""), "")
                    var idx = formats.indexOf(key)
                    if (idx < 0) { formats.add(key); idx = formats.size - 1 }
                    RunText(idx, texts[i])
                }
                val templates = formats.map { f -> zone[runs.indexOfFirst { formats[it.format] == f }] }
                sink.add(ZoneJob(runs) { regions -> rewrite(zone, templates, regions) })
            }
        }

        private fun rewrite(old: List<Element>, templates: List<Element>, regions: List<Region>) {
            val first = old.first()
            val parent = first.parentNode
            val doc = first.ownerDocument
            for (reg in regions) {
                // Line breaks inside a translated region become a:br elements between runs.
                val lines = reg.text.split('\n')
                lines.forEachIndexed { li, line ->
                    if (li > 0) {
                        val br = doc.createElementNS(A, "a:br")
                        Xml.child(templates[reg.format], A, "rPr")?.let { br.appendChild(it.cloneNode(true)) }
                        parent.insertBefore(br, first)
                    }
                    if (line.isEmpty()) return@forEachIndexed
                    val run = templates[reg.format].cloneNode(true) as Element
                    val t = Xml.child(run, A, "t") ?: doc.createElementNS(A, "a:t").also { run.appendChild(it) }
                    t.textContent = line.replace('\t', ' ')
                    parent.insertBefore(run, first)
                }
            }
            for (e in old) parent.removeChild(e)
        }

        fun setLanguage(doc: Document, lang: String) {
            for (tag in listOf("rPr", "endParaRPr", "defRPr")) for (e in Xml.all(doc, A, tag)) {
                val v = e.getAttribute("lang")
                if (v.isNotEmpty() && v.lowercase().startsWith("fr")) e.setAttribute("lang", lang)
            }
        }
    }

    override fun collect(pkg: ZipPackage, options: TranslateOptions, sink: MutableList<ZoneJob>, stats: DocStats): () -> Unit {
        val docs = LinkedHashMap<String, Document>()
        val pattern = Regex(
            "ppt/(slides/slide|slideLayouts/slideLayout|slideMasters/slideMaster|charts/chart|diagrams/data|diagrams/drawing" +
                (if (options.translateNotes) "|notesSlides/notesSlide" else "") +
                (if (options.translateComments) "|comments/comment" else "") + ")\\d*\\.xml",
        )
        for (name in pkg.names()) {
            if (!pattern.matches(name)) continue
            val doc = Xml.parse(pkg[name]!!)
            docs[name] = doc
            stats.parts++
            collectDrawingParagraphs(doc, sink, stats)
            if (name.startsWith("ppt/comments/")) collectComments(doc, sink, stats)
        }
        return {
            for ((name, doc) in docs) {
                setLanguage(doc, options.targetLanguage)
                pkg[name] = Xml.serialize(doc)
            }
        }
    }

    private fun collectComments(doc: Document, sink: MutableList<ZoneJob>, stats: DocStats) {
        val nl = doc.getElementsByTagNameNS("*", "text")
        for (i in 0 until nl.length) {
            val e = nl.item(i) as Element
            if (Xml.children(e).isNotEmpty()) continue
            val text = e.textContent ?: continue
            stats.paragraphs++
            sink.add(ZoneJob(listOf(RunText(0, text))) { regions -> e.textContent = regions.joinToString("") { it.text } })
        }
    }
}
