package app.carnet.core.convert

import app.carnet.core.doc.PlainText
import app.carnet.core.doc.Xml
import app.carnet.core.doc.ZipPackage
import org.w3c.dom.Element
import org.w3c.dom.Text
import java.io.InputStream

object Readers {
    /** Reads any supported file into a [DocModel] (for conversion and previews). */
    fun read(name: String, input: InputStream): DocModel = when (name.substringAfterLast('.').lowercase()) {
        "docx", "docm", "dotx" -> DocxReader.read(input)
        "odt", "ott", "odp", "ods" -> OdfReader(ZipPackage.read(input)).read()
        "pptx", "ppsx", "potx" -> PptxReader(ZipPackage.read(input)).read()
        "xlsx", "xlsm" -> XlsxReader(ZipPackage.read(input)).read()
        "md" -> TextReader.markdown(String(input.readBytes(), Charsets.UTF_8))
        "csv" -> TextReader.csv(String(input.readBytes(), Charsets.UTF_8))
        "html", "htm" -> TextReader.plain(String(input.readBytes(), Charsets.UTF_8).replace(Regex("<[^>]+>"), " ").replace(Regex("[ \\t]+"), " "))
        else -> TextReader.plain(String(input.readBytes(), Charsets.UTF_8))
    }

    fun parseColor(v: String?): Int? = v?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
    fun length(v: String?): Float? {
        if (v.isNullOrEmpty()) return null
        val n = Regex("^-?[0-9.]+").find(v)?.value?.toFloatOrNull() ?: return null
        return when {
            v.endsWith("cm") -> n * 28.3465f; v.endsWith("mm") -> n * 2.83465f; v.endsWith("in") -> n * 72f
            v.endsWith("pt") -> n; v.endsWith("pc") -> n * 12f; v.endsWith("px") -> n * 0.75f; else -> n
        }
    }
}

// ---------------------------------------------------------------------------------- Text / Markdown / CSV
object TextReader {
    private val body = CharStyle(size = 11f, font = "Calibri")

    fun plain(text: String): DocModel {
        val paras = text.replace("\r\n", "\n").split(Regex("\n\\s*\n")).map { p ->
            Paragraph(listOf(TextRun(p.trimEnd('\n'), body)), ParaStyle(spaceAfter = 8f))
        }
        return DocModel(listOf(Section(PageSetup(), paras)))
    }

    fun markdown(text: String): DocModel {
        val blocks = ArrayList<BlockNode>()
        var inCode = false
        for (raw in text.replace("\r\n", "\n").split("\n")) {
            if (raw.trimStart().startsWith("```")) { inCode = !inCode; continue }
            if (inCode) { blocks.add(Paragraph(listOf(TextRun(raw, CharStyle(size = 9.5f, font = "Courier New"))), ParaStyle(spaceAfter = 0f, shading = 0xFFF2F2F2.toInt()))); continue }
            if (raw.isBlank()) continue
            val h = Regex("^(#{1,6})\\s+(.*)").find(raw)
            if (h != null) {
                val lvl = h.groupValues[1].length
                blocks.add(Paragraph(inline(h.groupValues[2], CharStyle(bold = true, size = listOf(20f, 16f, 13.5f, 12f, 11f, 11f)[lvl - 1], font = "Calibri")), ParaStyle(spaceBefore = 10f, spaceAfter = 4f, heading = lvl, keepWithNext = true)))
                continue
            }
            val li = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)").find(raw)
            if (li != null) {
                val depth = li.groupValues[1].length / 2
                val label = if (li.groupValues[2][0].isDigit()) li.groupValues[2] else "•"
                blocks.add(Paragraph(inline(li.groupValues[3], body), ParaStyle(indentLeft = 18f * (depth + 1), firstLine = -14f, listLabel = label, spaceAfter = 2f)))
                continue
            }
            if (raw.startsWith(">")) { blocks.add(Paragraph(inline(raw.removePrefix(">").trim(), body.copy(italic = true, color = 0xFF555555.toInt())), ParaStyle(indentLeft = 18f, spaceAfter = 6f))); continue }
            blocks.add(Paragraph(inline(raw, body), ParaStyle(spaceAfter = 6f)))
        }
        return DocModel(listOf(Section(PageSetup(), blocks)))
    }

    /** **bold**, *italic*, `code` */
    private fun inline(s: String, base: CharStyle): List<Inline> {
        val out = ArrayList<Inline>()
        val re = Regex("(\\*\\*[^*]+\\*\\*|__[^_]+__|\\*[^*]+\\*|_[^_]+_|`[^`]+`)")
        var last = 0
        for (m in re.findAll(s)) {
            if (m.range.first > last) out.add(TextRun(s.substring(last, m.range.first), base))
            val v = m.value
            out.add(when {
                v.startsWith("**") || v.startsWith("__") -> TextRun(v.substring(2, v.length - 2), base.copy(bold = true))
                v.startsWith("`") -> TextRun(v.substring(1, v.length - 1), base.copy(font = "Courier New"))
                else -> TextRun(v.substring(1, v.length - 1), base.copy(italic = true))
            })
            last = m.range.last + 1
        }
        if (last < s.length) out.add(TextRun(s.substring(last), base))
        return out
    }

    fun csv(text: String): DocModel {
        val sep = if (text.count { it == ';' } > text.count { it == ',' }) ';' else ','
        val rows = PlainText.parseCsv(text, sep)
        val n = rows.maxOfOrNull { it.size } ?: 1
        val st = CharStyle(size = 9.5f, font = "Calibri")
        val t = Table(rows.mapIndexed { i, r -> Row((0 until n).map { c -> Cell(listOf(Paragraph(listOf(TextRun(r.getOrElse(c) { "" }, if (i == 0) st.copy(bold = true) else st)), ParaStyle(spaceAfter = 0f)))) }, i == 0) }, emptyList())
        return DocModel(listOf(Section(PageSetup(width = 841.9f, height = 595.3f, marginLeft = 36f, marginRight = 36f, marginTop = 36f, marginBottom = 36f), listOf(t))))
    }
}

// ---------------------------------------------------------------------------------- Excel
class XlsxReader(private val pkg: ZipPackage) {
    private val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

    fun read(): DocModel {
        val shared = pkg["xl/sharedStrings.xml"]?.let { b -> Xml.all(Xml.parse(b), S, "si").map { it.textContent } } ?: emptyList()
        val wb = Xml.parse(pkg["xl/workbook.xml"] ?: error("Not an Excel file"))
        val rels = HashMap<String, String>()
        pkg["xl/_rels/workbook.xml.rels"]?.let { b -> val d = Xml.parse(b); val nl = d.getElementsByTagName("Relationship"); for (i in 0 until nl.length) { val r = nl.item(i) as Element; rels[r.getAttribute("Id")] = r.getAttribute("Target") } }
        val sections = ArrayList<Section>()
        for (sheet in Xml.all(wb, S, "sheet")) {
            val rid = sheet.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
            val target = rels[rid] ?: continue
            val path = if (target.startsWith("/")) target.substring(1) else "xl/$target"
            val d = Xml.parse(pkg[path] ?: continue)
            val grid = HashMap<Pair<Int, Int>, String>()
            var maxR = 0; var maxC = 0
            for (c in Xml.all(d, S, "c")) {
                val ref = c.getAttribute("r")
                val col = ref.takeWhile { it.isLetter() }.fold(0) { a, ch -> a * 26 + (ch.uppercaseChar() - 'A' + 1) } - 1
                val row = ref.dropWhile { it.isLetter() }.toIntOrNull()?.minus(1) ?: continue
                val t = c.getAttribute("t")
                val v = Xml.child(c, S, "v")?.textContent
                val value = when (t) {
                    "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                    "inlineStr" -> Xml.child(c, S, "is")?.textContent ?: ""
                    "b" -> if (v == "1") "TRUE" else "FALSE"
                    else -> v?.let { num -> num.toDoubleOrNull()?.let { if (it == Math.floor(it) && kotlin.math.abs(it) < 1e15) it.toLong().toString() else "%.4g".format(it) } ?: num } ?: ""
                }
                if (value.isEmpty()) continue
                grid[row to col] = value; maxR = maxOf(maxR, row); maxC = maxOf(maxC, col)
            }
            if (grid.isEmpty()) continue
            val widths = (0..maxC).map { c -> (0..maxR).maxOf { r -> (grid[r to c]?.length ?: 0) }.coerceIn(4, 40).toFloat() }
            val st = CharStyle(size = 9f, font = "Calibri")
            val rows = (0..maxR).map { r -> Row((0..maxC).map { c -> Cell(listOf(Paragraph(listOf(TextRun(grid[r to c] ?: "", if (r == 0) st.copy(bold = true) else st)), ParaStyle(spaceAfter = 0f)))) }, r == 0) }
            val landscape = widths.sum() > 60
            val page = if (landscape) PageSetup(841.9f, 595.3f, 36f, 36f, 36f, 36f) else PageSetup(marginLeft = 36f, marginRight = 36f, marginTop = 48f, marginBottom = 48f)
            val title = Paragraph(listOf(TextRun(sheet.getAttribute("name"), CharStyle(bold = true, size = 13f, font = "Calibri"))), ParaStyle(spaceAfter = 6f))
            sections.add(Section(page, listOf(title, Table(rows, widths))))
        }
        return DocModel(sections.ifEmpty { listOf(Section()) })
    }
}

// ---------------------------------------------------------------------------------- OpenDocument
class OdfReader(private val pkg: ZipPackage) {
    private val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    private val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    private val FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
    private val TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    private val DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    private val XLINK = "http://www.w3.org/1999/xlink"
    private val SVG = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"

    private class OStyle(val parent: String?, val text: Element?, val para: Element?)
    private val styles = HashMap<String, OStyle>()

    fun read(): DocModel {
        var page = PageSetup()
        for (name in listOf("styles.xml", "content.xml")) {
            val d = Xml.parse(pkg[name] ?: continue)
            for (s in Xml.all(d, STYLE, "style")) styles[s.getAttributeNS(STYLE, "name")] = OStyle(s.getAttributeNS(STYLE, "parent-style-name").ifEmpty { null },
                Xml.child(s, STYLE, "text-properties"), Xml.child(s, STYLE, "paragraph-properties"))
            if (name == "styles.xml") Xml.all(d, STYLE, "page-layout-properties").firstOrNull()?.let { p ->
                page = PageSetup(Readers.length(p.getAttributeNS(FO, "page-width")) ?: page.width, Readers.length(p.getAttributeNS(FO, "page-height")) ?: page.height,
                    Readers.length(p.getAttributeNS(FO, "margin-top")) ?: 56f, Readers.length(p.getAttributeNS(FO, "margin-bottom")) ?: 56f,
                    Readers.length(p.getAttributeNS(FO, "margin-left")) ?: 56f, Readers.length(p.getAttributeNS(FO, "margin-right")) ?: 56f)
            }
        }
        val content = Xml.parse(pkg["content.xml"] ?: error("Not an OpenDocument file"))
        val body = Xml.all(content, "urn:oasis:names:tc:opendocument:xmlns:office:1.0", "body").first()
        val blocks = ArrayList<BlockNode>()
        fun walk(e: Element, depth: Int) {
            for (c in Xml.children(e)) when {
                c.namespaceURI == TEXT && (c.localName == "p" || c.localName == "h") -> blocks.add(paragraph(c, null, depth))
                c.namespaceURI == TEXT && c.localName == "list" -> for (li in Xml.children(c)) for (x in Xml.children(li)) {
                    if (x.localName == "p" || x.localName == "h") blocks.add(paragraph(x, "•", depth + 1)) else if (x.localName == "list") walk(li, depth + 1)
                }
                c.namespaceURI == TABLE && c.localName == "table" -> blocks.add(table(c))
                else -> walk(c, depth)
            }
        }
        walk(body, 0)
        return DocModel(listOf(Section(page, blocks)))
    }

    private fun charStyle(name: String?, base: CharStyle): CharStyle {
        val chainList = ArrayList<OStyle>()
        var n = name; var g = 0
        while (n != null && g++ < 10) { val s = styles[n] ?: break; chainList.add(0, s); n = s.parent }
        var st = base
        for (s in chainList) s.text?.let { t ->
            if (t.getAttributeNS(FO, "font-weight") == "bold") st = st.copy(bold = true)
            if (t.getAttributeNS(FO, "font-style") == "italic") st = st.copy(italic = true)
            Readers.length(t.getAttributeNS(FO, "font-size"))?.let { if (!t.getAttributeNS(FO, "font-size").endsWith("%")) st = st.copy(size = it) }
            Readers.parseColor(t.getAttributeNS(FO, "color"))?.let { st = st.copy(color = it) }
            Readers.parseColor(t.getAttributeNS(FO, "background-color"))?.let { st = st.copy(highlight = it) }
            if (t.getAttributeNS(STYLE, "text-underline-style").let { it.isNotEmpty() && it != "none" }) st = st.copy(underline = true)
            t.getAttributeNS(STYLE, "font-name").takeIf { it.isNotEmpty() }?.let { st = st.copy(font = it) }
        }
        return st
    }

    private fun paragraph(p: Element, label: String?, depth: Int): Paragraph {
        val styleName = p.getAttributeNS(TEXT, "style-name")
        val isH = p.localName == "h"
        val level = p.getAttributeNS(TEXT, "outline-level").toIntOrNull() ?: 1
        val base = charStyle(styleName, CharStyle(size = if (isH) listOf(18f, 15f, 13f, 12f).getOrElse(level - 1) { 12f } else 11f, bold = isH, font = "Liberation Serif"))
        var align = HAlign.LEFT
        styles[styleName]?.para?.getAttributeNS(FO, "text-align")?.let { align = when (it) { "center" -> HAlign.CENTER; "end", "right" -> HAlign.RIGHT; "justify" -> HAlign.JUSTIFY; else -> HAlign.LEFT } }
        val inlines = ArrayList<Inline>()
        fun walk(e: Element, st: CharStyle) {
            var c = e.firstChild
            while (c != null) {
                when {
                    c is Text -> inlines.add(TextRun(c.data, st))
                    c is Element && c.localName == "span" -> walk(c, charStyle(c.getAttributeNS(TEXT, "style-name"), st))
                    c is Element && c.localName == "a" -> walk(c, st)
                    c is Element && c.localName == "s" -> inlines.add(TextRun(" ".repeat(c.getAttributeNS(TEXT, "c").toIntOrNull() ?: 1), st))
                    c is Element && c.localName == "tab" -> inlines.add(TextRun("\t", st))
                    c is Element && c.localName == "line-break" -> inlines.add(TextRun("\n", st))
                    c is Element && c.localName == "frame" -> {
                        val img = Xml.child(c, DRAW, "image")
                        val href = img?.getAttributeNS(XLINK, "href")
                        val data = href?.let { pkg[it] }
                        if (data != null) inlines.add(ImageRun(data, Readers.length(c.getAttributeNS(SVG, "width")) ?: 200f, Readers.length(c.getAttributeNS(SVG, "height")) ?: 150f))
                    }
                }
                c = c.nextSibling
            }
        }
        walk(p, base)
        return Paragraph(inlines, ParaStyle(align = align, spaceBefore = if (isH) 10f else 0f, spaceAfter = if (isH) 4f else 6f, heading = if (isH) level else 0,
            listLabel = label, indentLeft = if (label != null) 18f * depth else 0f, firstLine = if (label != null) -14f else 0f, keepWithNext = isH))
    }

    private fun table(t: Element): Table {
        val rows = ArrayList<Row>()
        fun collectRows(e: Element) {
            for (c in Xml.children(e)) {
                if (c.localName == "table-row") {
                    val cells = Xml.children(c).filter { it.localName == "table-cell" }.flatMap { cell ->
                        val rep = cell.getAttributeNS(TABLE, "number-columns-repeated").toIntOrNull()?.coerceAtMost(30) ?: 1
                        val blocks = Xml.children(cell).filter { it.localName == "p" || it.localName == "h" }.map { paragraph(it, null, 0) }
                        List(rep) { Cell(blocks, cell.getAttributeNS(TABLE, "number-columns-spanned").toIntOrNull() ?: 1) }
                    }
                    rows.add(Row(cells))
                } else if (c.localName.startsWith("table-header-rows") || c.localName == "table-rows") collectRows(c)
            }
        }
        collectRows(t)
        return Table(rows, emptyList())
    }
}

// ---------------------------------------------------------------------------------- PowerPoint
class PptxReader(private val pkg: ZipPackage) {
    private val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val emu = 12700f

    private fun rels(part: String): Map<String, String> {
        val dir = part.substringBeforeLast('/'); val file = part.substringAfterLast('/')
        val b = pkg["$dir/_rels/$file.rels"] ?: return emptyMap()
        val d = Xml.parse(b); val nl = d.getElementsByTagName("Relationship")
        return (0 until nl.length).associate { i -> (nl.item(i) as Element).let { it.getAttribute("Id") to resolve(dir, it.getAttribute("Target")) } }
    }

    private fun resolve(dir: String, target: String): String {
        if (target.startsWith("/")) return target.substring(1)
        val parts = (dir.split('/') + target.split('/')).toMutableList()
        val out = ArrayList<String>()
        for (p in parts) if (p == "..") out.removeLastOrNull() else if (p.isNotEmpty() && p != ".") out.add(p)
        return out.joinToString("/")
    }

    private data class Ph(val type: String, val idx: String, val xfrm: FloatArray?, val defaultSize: Float?)

    /** Master text styles: per level (size pt, alignment, left margin pt, indent pt). */
    private data class LvlStyle(val size: Float?, val align: String?, val marL: Float?, val indent: Float?)
    private val masterStyles = HashMap<String, Map<String, List<LvlStyle>>>()

    private fun masterTextStyles(master: String?): Map<String, List<LvlStyle>> {
        if (master == null) return emptyMap()
        return masterStyles.getOrPut(master) {
            val d = Xml.parse(pkg[master] ?: return@getOrPut emptyMap())
            val tx = Xml.all(d, P, "txStyles").firstOrNull() ?: return@getOrPut emptyMap()
            Xml.children(tx).associate { st ->
                st.localName to (1..5).map { l ->
                    val lp = Xml.child(st, A, "lvl${l}pPr")
                    val rp = lp?.let { Xml.child(it, A, "defRPr") }
                    LvlStyle(rp?.getAttribute("sz")?.toFloatOrNull()?.div(100f), lp?.getAttribute("algn")?.ifEmpty { null },
                        lp?.getAttribute("marL")?.toFloatOrNull()?.div(emu), lp?.getAttribute("indent")?.toFloatOrNull()?.div(emu))
                }
            }
        }
    }

    private fun placeholders(part: String?): List<Ph> {
        if (part == null) return emptyList()
        val d = Xml.parse(pkg[part] ?: return emptyList())
        return Xml.all(d, P, "sp").mapNotNull { sp ->
            val ph = sp.getElementsByTagNameNS(P, "ph").item(0) as? Element ?: return@mapNotNull null
            Ph(ph.getAttribute("type").ifEmpty { "body" }, ph.getAttribute("idx"), xfrm(sp), null)
        }
    }

    private fun xfrm(e: Element): FloatArray? {
        val x = e.getElementsByTagNameNS(A, "xfrm").item(0) as? Element ?: return null
        val off = Xml.child(x, A, "off") ?: return null; val ext = Xml.child(x, A, "ext") ?: return null
        return floatArrayOf(off.getAttribute("x").toFloat() / emu, off.getAttribute("y").toFloat() / emu, ext.getAttribute("cx").toFloat() / emu, ext.getAttribute("cy").toFloat() / emu)
    }

    fun read(): DocModel {
        val pres = Xml.parse(pkg["ppt/presentation.xml"] ?: error("Not a PowerPoint file"))
        val sz = Xml.all(pres, P, "sldSz").firstOrNull()
        val page = PageSetup(sz?.getAttribute("cx")?.toFloat()?.div(emu) ?: 720f, sz?.getAttribute("cy")?.toFloat()?.div(emu) ?: 540f, 0f, 0f, 0f, 0f)
        val presRels = rels("ppt/presentation.xml")
        val slides = Xml.all(pres, P, "sldId").mapNotNull { presRels[it.getAttributeNS(R, "id")] }
        val pages = ArrayList<FixedPage>()
        for (slide in slides) {
            val d = Xml.parse(pkg[slide] ?: continue)
            val sr = rels(slide)
            val layout = sr.values.firstOrNull { it.contains("slideLayout") }
            val master = layout?.let { rels(it).values.firstOrNull { v -> v.contains("slideMaster") } }
            // Layout placeholders often inherit their position from the master: keep only ones with a position.
            val phs = (placeholders(layout) + placeholders(master)).filter { it.xfrm != null }
            val frames = ArrayList<Frame>()
            val images = ArrayList<Pair<ImageRun, FloatArray>>()
            for (sp in Xml.all(d, P, "sp")) {
                val ph = sp.getElementsByTagNameNS(P, "ph").item(0) as? Element
                val type = ph?.getAttribute("type")?.ifEmpty { "body" }
                val box = xfrm(sp) ?: phs.firstOrNull { it.idx.isNotEmpty() && it.idx == ph?.getAttribute("idx") }?.xfrm
                    ?: phs.firstOrNull { it.type == type || (type == "ctrTitle" && it.type == "title") || (type == "subTitle" && it.type == "body") }?.xfrm ?: continue
                val txBody = Xml.child(sp, P, "txBody") ?: continue
                val isTitle = type == "title" || type == "ctrTitle"
                val ms = masterTextStyles(master)
                val lvls = when { isTitle -> ms["titleStyle"]; ph != null -> ms["bodyStyle"]; else -> ms["otherStyle"] } ?: emptyList()
                val defSize = lvls.firstOrNull()?.size ?: when { isTitle -> 40f; type == "subTitle" -> 24f; ph != null -> 22f; else -> 18f }
                val fill = (Xml.child(sp, P, "spPr")?.let { Xml.child(it, A, "solidFill") })?.let { color(it) }
                val blocks = Xml.children(txBody).filter { it.localName == "p" }.map { paragraph(it, defSize, isTitle, ph != null && !isTitle && type != "subTitle", if (type == "ctrTitle" || type == "subTitle") listOf(LvlStyle(null, "ctr", null, null)) + lvls.drop(1) else lvls) }
                frames.add(Frame(box[0], box[1], box[2], box[3], blocks, fill, anchorMiddle = isTitle || type == "subTitle"))
            }
            for (pic in Xml.all(d, P, "pic")) {
                val blip = pic.getElementsByTagNameNS(A, "blip").item(0) as? Element ?: continue
                val path = sr[blip.getAttributeNS(R, "embed")] ?: continue
                val box = xfrm(pic) ?: continue
                pkg[path]?.let { images.add(ImageRun(it, box[2], box[3]) to box) }
            }
            for (gf in Xml.all(d, P, "graphicFrame")) {
                val tbl = gf.getElementsByTagNameNS(A, "tbl").item(0) as? Element ?: continue
                val box = xfrm(gf) ?: continue
                val cols = Xml.child(tbl, A, "tblGrid")?.let { g -> Xml.children(g).map { it.getAttribute("w").toFloat() / emu } } ?: emptyList()
                val rows = Xml.children(tbl).filter { it.localName == "tr" }.mapIndexed { ri, tr ->
                    Row(Xml.children(tr).filter { it.localName == "tc" }.map { tc ->
                        val body = Xml.child(tc, A, "txBody")
                        Cell(body?.let { b -> Xml.children(b).filter { it.localName == "p" }.map { paragraph(it, 14f, false, false) } } ?: emptyList(),
                            tc.getAttribute("gridSpan").toIntOrNull() ?: 1, if (ri == 0) 0xFFDCE4F5.toInt() else null)
                    }, ri == 0)
                }
                frames.add(Frame(box[0], box[1], box[2], box[3], listOf(Table(rows, cols))))
            }
            val bg = (d.getElementsByTagNameNS(P, "bg").item(0) as? Element)?.let { b -> (b.getElementsByTagNameNS(A, "srgbClr").item(0) as? Element)?.getAttribute("val") }?.let { Readers.parseColor(it) }
            pages.add(FixedPage(frames, bg ?: 0xFFFFFFFF.toInt(), images))
        }
        return DocModel(listOf(Section(page, fixedPages = pages)))
    }

    private fun color(fill: Element): Int? = (Xml.child(fill, A, "srgbClr")?.getAttribute("val"))?.let { Readers.parseColor(it) }

    private fun paragraph(p: Element, defSize0: Float, title: Boolean, bullets: Boolean, lvls: List<LvlStyle> = emptyList()): Paragraph {
        val pPr = Xml.child(p, A, "pPr")
        val lvl = pPr?.getAttribute("lvl")?.toIntOrNull() ?: 0
        val ls = lvls.getOrNull(lvl)
        val defSize = ls?.size ?: (defSize0 - lvl * 2f)
        val align = when (pPr?.getAttribute("algn")?.ifEmpty { null } ?: ls?.align) { "ctr" -> HAlign.CENTER; "r" -> HAlign.RIGHT; "just" -> HAlign.JUSTIFY; else -> HAlign.LEFT }
        val noBullet = pPr?.let { Xml.child(it, A, "buNone") } != null
        val buChar = pPr?.let { Xml.child(it, A, "buChar") }?.getAttribute("char")
        val size = defSize
        val inlines = ArrayList<Inline>()
        for (c in Xml.children(p)) {
            when (c.localName) {
                "r", "fld" -> {
                    val rPr = Xml.child(c, A, "rPr")
                    var st = CharStyle(size = rPr?.getAttribute("sz")?.toFloatOrNull()?.div(100f) ?: size, bold = rPr?.getAttribute("b") == "1" || (title && rPr?.getAttribute("b") != "0" && false),
                        italic = rPr?.getAttribute("i") == "1", underline = rPr?.getAttribute("u").let { it != null && it.isNotEmpty() && it != "none" }, font = "Calibri")
                    rPr?.let { Xml.child(it, A, "solidFill") }?.let { color(it) }?.let { st = st.copy(color = it) }
                    rPr?.let { Xml.child(it, A, "latin") }?.getAttribute("typeface")?.takeIf { it.isNotEmpty() && !it.startsWith("+") }?.let { st = st.copy(font = it) }
                    inlines.add(TextRun(Xml.child(c, A, "t")?.textContent ?: "", st))
                }
                "br" -> inlines.add(TextRun("\n", CharStyle(size = size)))
            }
        }
        val label = if (bullets && !noBullet && inlines.isNotEmpty()) (buChar ?: "•") else null
        val marL = ls?.marL ?: (22f + lvl * 24f); val ind = ls?.indent ?: -18f
        return Paragraph(inlines, ParaStyle(align = align, spaceBefore = if (bullets) size * 0.2f else 0f, spaceAfter = size * 0.1f, lineSpacing = 0.92f, listLabel = label,
            indentLeft = if (label != null) marL else 0f, firstLine = if (label != null) ind else 0f))
    }
}
