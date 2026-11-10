package app.carnet.core.convert

import app.carnet.core.doc.Xml
import app.carnet.core.doc.ZipPackage
import org.w3c.dom.Element
import java.io.InputStream

/** Reads a .docx into a [DocModel]: styles, lists, tables, images, page setup, headers/footers. */
class DocxReader(private val pkg: ZipPackage) {
    companion object {
        const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
        const val WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
        fun read(input: InputStream) = DocxReader(ZipPackage.read(input)).read()

        val highlightColors = mapOf("yellow" to 0xFFFFFF00, "green" to 0xFF00FF00, "cyan" to 0xFF00FFFF, "magenta" to 0xFFFF00FF, "blue" to 0xFF0000FF,
            "red" to 0xFFFF0000, "darkBlue" to 0xFF000080, "darkCyan" to 0xFF008080, "darkGreen" to 0xFF008000, "darkMagenta" to 0xFF800080,
            "darkRed" to 0xFF800000, "darkYellow" to 0xFF808000, "darkGray" to 0xFF808080, "lightGray" to 0xFFC0C0C0, "black" to 0xFF000000)
    }

    private class StyleDef(val basedOn: String?, val pPr: Element?, val rPr: Element?, val name: String)

    private val styles = HashMap<String, StyleDef>()
    private var defaultR: Element? = null
    private var defaultP: Element? = null
    private var defaultParaStyle: String? = null
    private val rels = HashMap<String, String>()
    private val numFmt = HashMap<String, Pair<String, String>>() // "numId:ilvl" → (format, lvlText)
    private val counters = HashMap<String, Int>()

    private var majorFont = "Calibri Light"
    private var minorFont = "Calibri"

    fun read(): DocModel {
        pkg["word/theme/theme1.xml"]?.let { b ->
            val d = Xml.parse(b)
            Xml.all(d, A, "majorFont").firstOrNull()?.let { Xml.child(it, A, "latin")?.getAttribute("typeface")?.takeIf { t -> t.isNotEmpty() }?.let { t -> majorFont = t } }
            Xml.all(d, A, "minorFont").firstOrNull()?.let { Xml.child(it, A, "latin")?.getAttribute("typeface")?.takeIf { t -> t.isNotEmpty() }?.let { t -> minorFont = t } }
        }
        pkg["word/styles.xml"]?.let { loadStyles(Xml.parse(it)) }
        pkg["word/_rels/document.xml.rels"]?.let { loadRels(it, rels) }
        pkg["word/numbering.xml"]?.let { loadNumbering(Xml.parse(it)) }
        val doc = Xml.parse(pkg["word/document.xml"] ?: error("Not a Word document"))
        val body = Xml.all(doc, W, "body").firstOrNull() ?: error("Empty document")
        val sections = ArrayList<Section>()
        var blocks = ArrayList<BlockNode>()
        for (e in Xml.children(body)) {
            if (e.namespaceURI != W) continue
            when (e.localName) {
                "p" -> {
                    blocks.addAll(paragraph(e))
                    // A paragraph carrying sectPr ends a section.
                    Xml.child(e, W, "pPr")?.let { Xml.child(it, W, "sectPr") }?.let { sp -> sections.add(section(sp, ArrayList(collapseContextual(blocks)))); blocks = ArrayList() }
                }
                "tbl" -> blocks.add(table(e))
                "sdt" -> Xml.child(e, W, "sdtContent")?.let { c -> for (x in Xml.children(c)) if (x.localName == "p") blocks.addAll(paragraph(x)) else if (x.localName == "tbl") blocks.add(table(x)) }
                "sectPr" -> { sections.add(section(e, ArrayList(collapseContextual(blocks)))); blocks = ArrayList() }
            }
        }
        if (blocks.isNotEmpty()) sections.add(Section(blocks = collapseContextual(blocks)))
        val title = pkg["docProps/core.xml"]?.let { Regex("<dc:title>([^<]*)</dc:title>").find(String(it))?.groupValues?.get(1) }
        return DocModel(sections, title)
    }

    private fun loadRels(bytes: ByteArray, into: MutableMap<String, String>) {
        val d = Xml.parse(bytes)
        val nl = d.getElementsByTagName("Relationship")
        for (i in 0 until nl.length) { val r = nl.item(i) as Element; into[r.getAttribute("Id")] = r.getAttribute("Target") }
    }

    private fun loadStyles(d: org.w3c.dom.Document) {
        Xml.all(d, W, "docDefaults").firstOrNull()?.let { dd ->
            defaultR = Xml.child(dd, W, "rPrDefault")?.let { Xml.child(it, W, "rPr") }
            defaultP = Xml.child(dd, W, "pPrDefault")?.let { Xml.child(it, W, "pPr") }
        }
        for (s in Xml.all(d, W, "style")) {
            val id = s.getAttributeNS(W, "styleId")
            styles[id] = StyleDef(Xml.child(s, W, "basedOn")?.getAttributeNS(W, "val"), Xml.child(s, W, "pPr"), Xml.child(s, W, "rPr"),
                Xml.child(s, W, "name")?.getAttributeNS(W, "val") ?: id)
            if (s.getAttributeNS(W, "type") == "paragraph" && s.getAttributeNS(W, "default") == "1") defaultParaStyle = id
        }
    }

    private fun loadNumbering(d: org.w3c.dom.Document) {
        val abstract = HashMap<String, Element>()
        for (a in Xml.all(d, W, "abstractNum")) abstract[a.getAttributeNS(W, "abstractNumId")] = a
        for (n in Xml.all(d, W, "num")) {
            val id = n.getAttributeNS(W, "numId")
            val abs = abstract[Xml.child(n, W, "abstractNumId")?.getAttributeNS(W, "val")] ?: continue
            for (lvl in Xml.children(abs).filter { it.localName == "lvl" }) {
                val ilvl = lvl.getAttributeNS(W, "ilvl")
                numFmt["$id:$ilvl"] = (Xml.child(lvl, W, "numFmt")?.getAttributeNS(W, "val") ?: "bullet") to (Xml.child(lvl, W, "lvlText")?.getAttributeNS(W, "val") ?: "•")
            }
        }
    }

    private fun twip(e: Element?, attr: String): Float? = e?.getAttributeNS(W, attr)?.takeIf { it.isNotEmpty() }?.toFloatOrNull()?.div(20f)

    private fun section(sp: Element, blocks: List<BlockNode>): Section {
        val sz = Xml.child(sp, W, "pgSz"); val mar = Xml.child(sp, W, "pgMar")
        val page = PageSetup(
            width = twip(sz, "w") ?: 612f, height = twip(sz, "h") ?: 792f,
            marginTop = twip(mar, "top") ?: 72f, marginBottom = twip(mar, "bottom") ?: 72f,
            marginLeft = twip(mar, "left") ?: 72f, marginRight = twip(mar, "right") ?: 72f,
            headerY = twip(mar, "header") ?: 36f, footerY = twip(mar, "footer") ?: 36f,
        )
        fun part(kind: String): List<BlockNode> {
            val ref = Xml.children(sp).firstOrNull { it.localName == "${kind}Reference" && (it.getAttributeNS(W, "type") == "default" || it.getAttributeNS(W, "type").isEmpty()) } ?: return emptyList()
            val target = rels[ref.getAttributeNS(R, "id")] ?: return emptyList()
            val bytes = pkg["word/$target"] ?: return emptyList()
            val partRels = HashMap<String, String>()
            pkg["word/_rels/$target.rels"]?.let { loadRels(it, partRels) }
            val saved = HashMap(rels); rels.putAll(partRels)
            val d = Xml.parse(bytes)
            val out = Xml.children(d.documentElement).flatMap { if (it.localName == "p") paragraph(it) else if (it.localName == "tbl") listOf(table(it)) else emptyList() }
            rels.clear(); rels.putAll(saved)
            return out
        }
        return Section(page, blocks, part("header"), part("footer"))
    }

    // ---------------------------------------------------------------- styles

    private fun chain(styleId: String?): List<StyleDef> {
        val out = ArrayList<StyleDef>()
        var id = styleId
        var guard = 0
        while (id != null && guard++ < 20) { val s = styles[id] ?: break; out.add(0, s); id = s.basedOn }
        return out
    }

    private fun applyR(base: CharStyle, rPr: Element?): CharStyle {
        if (rPr == null) return base
        var s = base
        fun on(tag: String): Boolean? = Xml.child(rPr, W, tag)?.let { val v = it.getAttributeNS(W, "val"); v.isEmpty() || v == "1" || v == "true" || v == "on" }
        on("b")?.let { s = s.copy(bold = it) }
        on("i")?.let { s = s.copy(italic = it) }
        on("strike")?.let { s = s.copy(strike = it) }
        Xml.child(rPr, W, "u")?.let { s = s.copy(underline = it.getAttributeNS(W, "val") != "none") }
        Xml.child(rPr, W, "sz")?.getAttributeNS(W, "val")?.toFloatOrNull()?.let { s = s.copy(size = it / 2f) }
        Xml.child(rPr, W, "color")?.getAttributeNS(W, "val")?.let { v -> parseHex(v)?.let { s = s.copy(color = it) } }
        Xml.child(rPr, W, "highlight")?.getAttributeNS(W, "val")?.let { v -> highlightColors[v]?.let { s = s.copy(highlight = it.toInt()) } }
        Xml.child(rPr, W, "shd")?.getAttributeNS(W, "fill")?.let { v -> parseHex(v)?.let { s = s.copy(highlight = it) } }
        Xml.child(rPr, W, "rFonts")?.let { f ->
            val direct = f.getAttributeNS(W, "ascii").ifEmpty { f.getAttributeNS(W, "hAnsi") }
            val theme = f.getAttributeNS(W, "asciiTheme").ifEmpty { f.getAttributeNS(W, "hAnsiTheme") }
            when {
                direct.isNotEmpty() -> s = s.copy(font = direct)
                theme.startsWith("major") -> s = s.copy(font = majorFont)
                theme.startsWith("minor") -> s = s.copy(font = minorFont)
            }
        }
        Xml.child(rPr, W, "vertAlign")?.getAttributeNS(W, "val")?.let { s = s.copy(vertAlign = when (it) { "superscript" -> 1; "subscript" -> -1; else -> 0 }) }
        return s
    }

    private fun parseHex(v: String): Int? = if (v.length == 6 && v != "auto") v.toIntOrNull(16)?.let { (0xFF000000.toInt()) or it } else null

    private fun baseChar(styleId: String?): CharStyle {
        var s = applyR(CharStyle(size = 10f, font = "Times New Roman"), defaultR)
        for (d in chain(styleId ?: defaultParaStyle)) s = applyR(s, d.rPr)
        // Theme fonts (+minorHAnsi) → Calibri, the Office default.
        if (s.font.startsWith("+") || s.font.isEmpty()) s = s.copy(font = "Calibri")
        return s
    }

    private fun paraProps(pPr: Element?, styleId: String?): ParaStyle {
        // Inside a table, the table style's paragraph settings (e.g. "Table Grid": no spacing) come before the paragraph's own style.
        val pprs = listOfNotNull(defaultP) + (tableStyle?.let { chain(it).mapNotNull { s -> s.pPr } } ?: emptyList()) +
            chain(styleId ?: defaultParaStyle).mapNotNull { it.pPr } + listOfNotNull(pPr)
        var st = ParaStyle(spaceAfter = 0f, lineSpacing = 1f)
        val name = (styles[styleId ?: defaultParaStyle]?.name ?: "").lowercase()
        val heading = Regex("heading (\\d)").find(name)?.groupValues?.get(1)?.toInt() ?: if (name == "title") 1 else 0
        for (p in pprs) {
            Xml.child(p, W, "jc")?.getAttributeNS(W, "val")?.let { st = st.copy(align = when (it) { "center" -> HAlign.CENTER; "right", "end" -> HAlign.RIGHT; "both", "distribute" -> HAlign.JUSTIFY; else -> HAlign.LEFT }) }
            Xml.child(p, W, "spacing")?.let { sp ->
                twip(sp, "before")?.let { st = st.copy(spaceBefore = it) }
                twip(sp, "after")?.let { st = st.copy(spaceAfter = it) }
                sp.getAttributeNS(W, "line").toFloatOrNull()?.let { l -> if (sp.getAttributeNS(W, "lineRule").let { it == "auto" || it.isEmpty() }) st = st.copy(lineSpacing = (l / 240f).coerceIn(0.8f, 3f)) }
            }
            Xml.child(p, W, "ind")?.let { ind ->
                (twip(ind, "left") ?: twip(ind, "start"))?.let { st = st.copy(indentLeft = it) }
                (twip(ind, "right") ?: twip(ind, "end"))?.let { st = st.copy(indentRight = it) }
                twip(ind, "firstLine")?.let { st = st.copy(firstLine = it) }
                twip(ind, "hanging")?.let { st = st.copy(firstLine = -it) }
            }
            if (Xml.child(p, W, "pageBreakBefore") != null) st = st.copy(pageBreakBefore = true)
            if (Xml.child(p, W, "keepNext") != null) st = st.copy(keepWithNext = true)
            Xml.child(p, W, "pBdr")?.let { Xml.child(it, W, "bottom") }?.let { b ->
                val v = b.getAttributeNS(W, "val")
                st = if (v.isEmpty() || v == "none" || v == "nil") st.copy(borderBottom = null)
                else st.copy(borderBottom = parseHex(b.getAttributeNS(W, "color")) ?: 0xFF000000.toInt(),
                    borderWidth = (b.getAttributeNS(W, "sz").toFloatOrNull() ?: 4f) / 8f)
            }
            Xml.child(p, W, "contextualSpacing")?.let { c -> st = st.copy(contextual = c.getAttributeNS(W, "val").let { it != "0" && it != "false" }) }
            Xml.child(p, W, "shd")?.getAttributeNS(W, "fill")?.let { v -> parseHex(v)?.let { st = st.copy(shading = it) } }
        }
        return st.copy(heading = heading, keepWithNext = st.keepWithNext || heading > 0, styleId = styleId ?: defaultParaStyle)
    }

    // ---------------------------------------------------------------- content

    private fun paragraph(p: Element): List<BlockNode> {
        val pPr = Xml.child(p, W, "pPr")
        val styleId = pPr?.let { Xml.child(it, W, "pStyle") }?.getAttributeNS(W, "val")
        var ps = paraProps(pPr, styleId)
        // Lists: numbering from the paragraph or its style.
        val numPr = pPr?.let { Xml.child(it, W, "numPr") } ?: chain(styleId).mapNotNull { it.pPr?.let { p -> Xml.child(p, W, "numPr") } }.lastOrNull()
        if (numPr != null) {
            val id = Xml.child(numPr, W, "numId")?.getAttributeNS(W, "val") ?: "0"
            val lvl = Xml.child(numPr, W, "ilvl")?.getAttributeNS(W, "val") ?: "0"
            if (id != "0") {
                val (fmt, text) = numFmt["$id:$lvl"] ?: ("bullet" to "•")
                val key = "$id:$lvl"
                val n = (counters[key] ?: 0) + 1; counters[key] = n
                // Reset deeper levels.
                counters.keys.filter { it.startsWith("$id:") && it.substringAfter(':').toInt() > lvl.toInt() }.forEach { counters.remove(it) }
                val label = when (fmt) {
                    "bullet" -> if (text.isEmpty() || text.first().code in 0xF000..0xF0FF) "•" else text
                    "lowerLetter" -> text.replace("%${lvl.toInt() + 1}", ('a' + (n - 1) % 26).toString())
                    "upperLetter" -> text.replace("%${lvl.toInt() + 1}", ('A' + (n - 1) % 26).toString())
                    "lowerRoman" -> text.replace("%${lvl.toInt() + 1}", roman(n).lowercase())
                    "upperRoman" -> text.replace("%${lvl.toInt() + 1}", roman(n))
                    else -> Regex("%(\\d)").replace(text) { m -> (if (m.groupValues[1].toInt() == lvl.toInt() + 1) n else counters["$id:${m.groupValues[1].toInt() - 1}"] ?: 1).toString() }
                }
                if (ps.indentLeft == 0f) ps = ps.copy(indentLeft = 18f * (lvl.toInt() + 1), firstLine = -18f)
                ps = ps.copy(listLabel = label)
            }
        }
        val base = baseChar(styleId)
        val inlines = ArrayList<Inline>()
        val extra = ArrayList<BlockNode>()
        var pageBreakAfter = false; var pageBreakFirst = false
        var inField = 0
        var fieldInstr = ""
        fun fieldRun(r: Element) {
            val st = applyR(base, Xml.child(r, W, "rPr"))
            for (c in Xml.children(r)) {
                when (c.localName) {
                    "fldChar" -> when (c.getAttributeNS(W, "fldCharType")) {
                        "begin" -> { inField = 1; fieldInstr = "" }
                        "separate" -> {
                            inField = 2
                            val kind = fieldInstr.trim().substringBefore(' ').uppercase()
                            if (kind == "PAGE" || kind == "NUMPAGES") { inlines.add(FieldRun(kind, st)); inField = 3 }
                        }
                        "end" -> inField = 0
                    }
                    "instrText" -> fieldInstr += c.textContent
                    "rPr" -> {}
                    else -> if (inField == 0 || inField == 2) when (c.localName) {
                        "t" -> inlines.add(TextRun(c.textContent, st))
                        "tab" -> inlines.add(TextRun("\t", st))
                        // A page break before any text starts the paragraph on a new page; later ones end it.
                        "br" -> if (c.getAttributeNS(W, "type") == "page") { if (inlines.none { it is TextRun && it.text.isNotBlank() }) pageBreakFirst = true else pageBreakAfter = true } else inlines.add(TextRun("\n", st))
                        "noBreakHyphen" -> inlines.add(TextRun("-", st))
                        "sym" -> inlines.add(TextRun("•", st))
                        "drawing" -> image(c)?.let { img -> inlines.add(floating(c, img) ?: img) }
                    }
                }
            }
        }
        fun walk(e: Element) {
            for (c in Xml.children(e)) {
                when (c.localName) {
                    "r" -> fieldRun(c)
                    "hyperlink", "ins", "smartTag", "sdt", "sdtContent", "customXml", "fldSimple" -> {
                        if (c.localName == "fldSimple" && c.getAttributeNS(W, "instr").trim().uppercase().startsWith("PAGE")) inlines.add(FieldRun("PAGE", base))
                        else walk(c)
                    }
                }
            }
        }
        walk(p)
        val out = ArrayList<BlockNode>()
        if (pageBreakFirst) out.add(PageBreak)
        // Empty paragraphs are as tall as their paragraph mark's font (covers are often spaced with them).
        val mark = pPr?.let { Xml.child(it, W, "rPr") }?.let { applyR(base, it) } ?: base
        out.add(Paragraph(inlines, ps.copy(markSize = mark.size)))
        if (pageBreakAfter) out.add(PageBreak)
        out.addAll(extra)
        return out
    }

    private fun roman(n: Int): String {
        val v = listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
        var x = n; val sb = StringBuilder()
        for ((k, s) in v) while (x >= k) { sb.append(s); x -= k }
        return sb.toString()
    }

    private fun image(drawing: Element): ImageRun? {
        val blips = drawing.getElementsByTagNameNS(A, "blip")
        if (blips.length == 0) return null
        val rid = (blips.item(0) as Element).getAttributeNS(R, "embed")
        val target = rels[rid] ?: return null
        val path = if (target.startsWith("/")) target.substring(1) else "word/$target"
        val data = pkg[path] ?: return null
        val ext = drawing.getElementsByTagNameNS(WP, "extent").item(0) as? Element
        val w = ext?.getAttribute("cx")?.toFloatOrNull()?.div(12700f) ?: 200f
        val h = ext?.getAttribute("cy")?.toFloatOrNull()?.div(12700f) ?: 150f
        val mime = if (path.endsWith(".png", true)) "image/png" else if (path.endsWith(".jpg", true) || path.endsWith(".jpeg", true)) "image/jpeg" else "image/" + path.substringAfterLast('.')
        return ImageRun(data, w, h, mime)
    }

    /** Anchored (floating) pictures, except "top and bottom" wrapping, which behaves like a picture on its own line. */
    private fun floating(drawing: Element, img: ImageRun): FloatingImage? {
        val a = Xml.children(drawing).firstOrNull { it.localName == "anchor" } ?: return null
        if (Xml.children(a).any { it.localName == "wrapTopAndBottom" }) return null
        fun pos(tag: String): Triple<String, String?, Float> {
            val e = Xml.children(a).firstOrNull { it.localName == tag } ?: return Triple("margin", null, 0f)
            val rel = e.getAttribute("relativeFrom").ifEmpty { "margin" }
            val align = Xml.children(e).firstOrNull { it.localName == "align" }?.textContent?.trim()
            val off = Xml.children(e).firstOrNull { it.localName == "posOffset" }?.textContent?.trim()?.toFloatOrNull()?.div(12700f) ?: 0f
            return Triple(rel, align, off)
        }
        val (hr, ha, ho) = pos("positionH"); val (vr, va, vo) = pos("positionV")
        return FloatingImage(img, hr, ha, ho, vr, va, vo)
    }

    private var tableStyle: String? = null

    /** Word drops the space between consecutive paragraphs of the same style when either asks for it (lists). */
    private fun collapseContextual(blocks: List<BlockNode>): List<BlockNode> {
        val out = blocks.toMutableList()
        for (i in 1 until out.size) {
            val a = out[i - 1] as? Paragraph ?: continue; val b = out[i] as? Paragraph ?: continue
            if (a.style.styleId == null || a.style.styleId != b.style.styleId) continue
            if (a.style.contextual) out[i - 1] = a.copy(style = a.style.copy(spaceAfter = 0f))
            if (b.style.contextual) out[i] = b.copy(style = b.style.copy(spaceBefore = 0f))
        }
        return out
    }

    private fun table(t: Element): Table {
        val tblPr = Xml.child(t, W, "tblPr")
        val styleId = tblPr?.let { Xml.child(it, W, "tblStyle") }?.getAttributeNS(W, "val")
        val outerStyle = tableStyle; tableStyle = styleId
        try { return tableBody(t, tblPr, styleId) } finally { tableStyle = outerStyle }
    }

    private fun tableBody(t: Element, tblPr: Element?, styleId: String?): Table {
        val grid = Xml.child(t, W, "tblGrid")?.let { g -> Xml.children(g).filter { it.localName == "gridCol" }.map { twip(it, "w") ?: 0f } } ?: emptyList()
        val bordersEl = tblPr?.let { Xml.child(it, W, "tblBorders") } ?: chain(styleId).mapNotNull { s -> s.pPr }.firstOrNull()?.let { null }
        val styleHasBorders = styleId != null && (styleId.contains("Grid", true) || styleId.contains("Table", true))
        val borders = bordersEl?.let { b -> Xml.children(b).any { it.getAttributeNS(W, "val").let { v -> v.isNotEmpty() && v != "none" && v != "nil" } } } ?: styleHasBorders
        val rows = ArrayList<Row>()
        for (tr in Xml.children(t).filter { it.localName == "tr" }) {
            val header = Xml.child(tr, W, "trPr")?.let { Xml.child(it, W, "tblHeader") } != null
            val cells = Xml.children(tr).filter { it.localName == "tc" }.map { tc ->
                val tcPr = Xml.child(tc, W, "tcPr")
                val span = tcPr?.let { Xml.child(it, W, "gridSpan") }?.getAttributeNS(W, "val")?.toIntOrNull() ?: 1
                val shd = tcPr?.let { Xml.child(it, W, "shd") }?.getAttributeNS(W, "fill")?.let { parseHex(it) }
                val blocks = Xml.children(tc).flatMap { if (it.localName == "p") paragraph(it) else if (it.localName == "tbl") listOf(table(it)) else emptyList() }
                Cell(collapseContextual(blocks.filter { it !is PageBreak }), span, shd)
            }
            rows.add(Row(cells, header))
        }
        return Table(rows, grid, borders)
    }
}
