package app.carnet.core.convert

import app.carnet.core.doc.ZipPackage
import java.io.OutputStream

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    .filter { it == '\t' || it == '\n' || it == '\r' || it.code >= 0x20 && it.code != 0xFFFE && it.code != 0xFFFF }

private fun hex(c: Int) = "%06X".format(c and 0xFFFFFF)

/** Writes a [DocModel] as a Word document. */
object DocxWriter {
    fun write(doc: DocModel, out: OutputStream) {
        val media = LinkedHashMap<String, ByteArray>()
        val rels = StringBuilder()
        var imageId = 0
        val body = StringBuilder()

        fun rPr(s: CharStyle): String {
            val sb = StringBuilder("<w:rPr>")
            sb.append("<w:rFonts w:ascii=\"${esc(s.font)}\" w:hAnsi=\"${esc(s.font)}\" w:cs=\"${esc(s.font)}\"/>")
            if (s.bold) sb.append("<w:b/>")
            if (s.italic) sb.append("<w:i/>")
            if (s.strike) sb.append("<w:strike/>")
            if ((s.color and 0xFFFFFF) != 0) sb.append("<w:color w:val=\"${hex(s.color)}\"/>")
            sb.append("<w:sz w:val=\"${(s.size * 2).toInt()}\"/>")
            s.highlight?.let { sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${hex(it)}\"/>") }
            if (s.underline) sb.append("<w:u w:val=\"single\"/>")
            if (s.vertAlign != 0) sb.append("<w:vertAlign w:val=\"${if (s.vertAlign > 0) "superscript" else "subscript"}\"/>")
            sb.append("<w:lang w:val=\"en-GB\"/></w:rPr>")
            return sb.toString()
        }

        fun runs(inl: List<Inline>): String {
            val sb = StringBuilder()
            for (i in inl) when (i) {
                is TextRun -> {
                    val parts = i.text.split('\n')
                    parts.forEachIndexed { k, part ->
                        if (k > 0) sb.append("<w:r>${rPr(i.style)}<w:br/></w:r>")
                        val tabs = part.split('\t')
                        tabs.forEachIndexed { t, seg ->
                            if (t > 0) sb.append("<w:r>${rPr(i.style)}<w:tab/></w:r>")
                            if (seg.isNotEmpty()) sb.append("<w:r>${rPr(i.style)}<w:t xml:space=\"preserve\">${esc(seg)}</w:t></w:r>")
                        }
                    }
                }
                is ImageRun -> {
                    imageId++
                    val ext = when { i.mime.contains("png") -> "png"; i.mime.contains("jp") -> "jpeg"; else -> i.mime.substringAfter('/') }
                    val name = "image$imageId.$ext"
                    media["word/media/$name"] = i.data
                    rels.append("<Relationship Id=\"rImg$imageId\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/$name\"/>")
                    val cx = (i.widthPt * 12700).toLong(); val cy = (i.heightPt * 12700).toLong()
                    sb.append("<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$imageId\" name=\"Picture $imageId\"/>" +
                        "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                        "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:nvPicPr><pic:cNvPr id=\"$imageId\" name=\"$name\"/><pic:cNvPicPr/></pic:nvPicPr>" +
                        "<pic:blipFill><a:blip r:embed=\"rImg$imageId\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
                        "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>")
                }
                is FieldRun -> sb.append("<w:r>${rPr(i.style)}<w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\"> ${i.kind} </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r>${rPr(i.style)}<w:t>1</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r>")
            }
            return sb.toString()
        }

        fun para(p: Paragraph): String {
            val st = p.style
            val jc = when (st.align) { HAlign.CENTER -> "center"; HAlign.RIGHT -> "right"; HAlign.JUSTIFY -> "both"; else -> "left" }
            val sb = StringBuilder("<w:p><w:pPr>")
            if (st.heading in 1..6) sb.append("<w:pStyle w:val=\"Heading${st.heading}\"/>")
            if (st.pageBreakBefore) sb.append("<w:pageBreakBefore/>")
            if (st.keepWithNext) sb.append("<w:keepNext/>")
            st.shading?.let { sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${hex(it)}\"/>") }
            sb.append("<w:spacing w:before=\"${(st.spaceBefore * 20).toInt()}\" w:after=\"${(st.spaceAfter * 20).toInt()}\" w:line=\"${(st.lineSpacing * 240).toInt()}\" w:lineRule=\"auto\"/>")
            val first = st.firstLine
            sb.append("<w:ind w:left=\"${(st.indentLeft * 20).toInt()}\" w:right=\"${(st.indentRight * 20).toInt()}\" " + (if (first < 0) "w:hanging=\"${(-first * 20).toInt()}\"" else "w:firstLine=\"${(first * 20).toInt()}\"") + "/>")
            sb.append("<w:jc w:val=\"$jc\"/></w:pPr>")
            val label = st.listLabel?.let { listOf(TextRun("$it\t", (p.inlines.firstOrNull { x -> x is TextRun } as? TextRun)?.style ?: CharStyle())) } ?: emptyList()
            sb.append(runs(label + p.inlines)).append("</w:p>")
            return sb.toString()
        }

        fun block(b: BlockNode): String = when (b) {
            is Paragraph -> para(b)
            is PageBreak -> "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>"
            is Table -> {
                val n = b.rows.maxOfOrNull { r -> r.cells.sumOf { it.span } } ?: 1
                val widths = if (b.columnWidths.size >= n) b.columnWidths.take(n) else List(n) { 450f / n }
                val sb = StringBuilder("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/>")
                if (b.borders) sb.append("<w:tblBorders>" + listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") { "<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"808080\"/>" } + "</w:tblBorders>")
                sb.append("</w:tblPr><w:tblGrid>")
                widths.forEach { sb.append("<w:gridCol w:w=\"${(it * 20).toInt()}\"/>") }
                sb.append("</w:tblGrid>")
                for (r in b.rows) {
                    sb.append("<w:tr>"); if (r.header) sb.append("<w:trPr><w:tblHeader/></w:trPr>")
                    for (c in r.cells) {
                        sb.append("<w:tc><w:tcPr>")
                        if (c.span > 1) sb.append("<w:gridSpan w:val=\"${c.span}\"/>")
                        c.shading?.let { sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${hex(it)}\"/>") }
                        sb.append("</w:tcPr>")
                        val inner = c.blocks.joinToString("") { block(it) }
                        sb.append(if (inner.contains("<w:p>") || inner.contains("<w:p ")) inner else "$inner<w:p/>")
                        sb.append("</w:tc>")
                    }
                    sb.append("</w:tr>")
                }
                sb.append("</w:tbl>")
                sb.toString()
            }
        }

        for ((si, sec) in doc.sections.withIndex()) {
            val blocks = sec.blocks + sec.fixedPages.flatMapIndexed { i, fp -> (if (i > 0) listOf(PageBreak) else emptyList()) + fp.frames.flatMap { it.blocks } }
            blocks.forEach { body.append(block(it)) }
            val pg = sec.page
            val sect = "<w:sectPr><w:pgSz w:w=\"${(pg.width * 20).toInt()}\" w:h=\"${(pg.height * 20).toInt()}\"" + (if (pg.width > pg.height) " w:orient=\"landscape\"" else "") + "/>" +
                "<w:pgMar w:top=\"${(pg.marginTop * 20).toInt()}\" w:right=\"${(pg.marginRight * 20).toInt()}\" w:bottom=\"${(pg.marginBottom * 20).toInt()}\" w:left=\"${(pg.marginLeft * 20).toInt()}\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>"
            if (si < doc.sections.size - 1) body.append("<w:p><w:pPr>$sect</w:pPr></w:p>") else body.append(sect)
        }

        val ns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
            "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
        val decl = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        val files = LinkedHashMap<String, ByteArray>()
        files["[Content_Types].xml"] = (decl + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/><Default Extension=\"jpg\" ContentType=\"image/jpeg\"/><Default Extension=\"gif\" ContentType=\"image/gif\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/></Types>").toByteArray()
        files["_rels/.rels"] = (decl + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/></Relationships>").toByteArray()
        files["docProps/core.xml"] = (decl + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
            "<dc:title>${esc(doc.title ?: "")}</dc:title><dc:creator>Carnet</dc:creator><dc:language>en-GB</dc:language></cp:coreProperties>").toByteArray()
        files["word/document.xml"] = (decl + "<w:document $ns><w:body>$body</w:body></w:document>").toByteArray()
        files["word/styles.xml"] = (decl + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\"/><w:sz w:val=\"22\"/><w:lang w:val=\"en-GB\"/></w:rPr></w:rPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>" +
            (1..6).joinToString("") { "<w:style w:type=\"paragraph\" w:styleId=\"Heading$it\"><w:name w:val=\"heading $it\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:keepNext/><w:outlineLvl w:val=\"${it - 1}\"/></w:pPr></w:style>" } +
            "</w:styles>").toByteArray()
        files["word/_rels/document.xml.rels"] = (decl + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>$rels</Relationships>").toByteArray()
        files.putAll(media)
        ZipPackage(files).write(out)
    }
}

/** Writes a [DocModel] as an OpenDocument text file. */
object OdtWriter {
    fun write(doc: DocModel, out: OutputStream) {
        val styles = LinkedHashMap<String, String>() // xml -> name
        val pics = LinkedHashMap<String, ByteArray>()
        fun charStyle(s: CharStyle): String {
            val props = StringBuilder("<style:text-properties style:font-name=\"${esc(s.font)}\" fo:font-size=\"${s.size}pt\"")
            if (s.bold) props.append(" fo:font-weight=\"bold\"")
            if (s.italic) props.append(" fo:font-style=\"italic\"")
            if (s.underline) props.append(" style:text-underline-style=\"solid\" style:text-underline-width=\"auto\" style:text-underline-color=\"font-color\"")
            if (s.strike) props.append(" style:text-line-through-style=\"solid\"")
            if ((s.color and 0xFFFFFF) != 0) props.append(" fo:color=\"#${hex(s.color)}\"")
            s.highlight?.let { props.append(" fo:background-color=\"#${hex(it)}\"") }
            if (s.vertAlign != 0) props.append(" style:text-position=\"${if (s.vertAlign > 0) "super" else "sub"} 58%\"")
            props.append(" fo:language=\"en\" fo:country=\"GB\"/>")
            return styles.getOrPut("T|$props") { "T${styles.size + 1}" }
        }
        fun paraStyle(p: ParaStyle): String {
            val align = when (p.align) { HAlign.CENTER -> "center"; HAlign.RIGHT -> "end"; HAlign.JUSTIFY -> "justify"; else -> "start" }
            val props = "<style:paragraph-properties fo:text-align=\"$align\" fo:margin-top=\"${p.spaceBefore}pt\" fo:margin-bottom=\"${p.spaceAfter}pt\" fo:margin-left=\"${p.indentLeft}pt\" fo:text-indent=\"${p.firstLine}pt\"" +
                (p.shading?.let { " fo:background-color=\"#${hex(it)}\"" } ?: "") + (if (p.pageBreakBefore) " fo:break-before=\"page\"" else "") + "/>"
            return styles.getOrPut("P|$props") { "P${styles.size + 1}" }
        }
        fun textOf(s: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '\t' -> sb.append("<text:tab/>")
                    c == '\n' -> sb.append("<text:line-break/>")
                    c == ' ' && (i == 0 || s[i - 1] == ' ') -> { var j = i; while (j < s.length && s[j] == ' ') j++; sb.append("<text:s text:c=\"${j - i}\"/>"); i = j; continue }
                    else -> sb.append(esc(c.toString()))
                }
                i++
            }
            return sb.toString()
        }
        fun para(p: Paragraph): String {
            val tag = if (p.style.heading > 0) "text:h text:outline-level=\"${p.style.heading}\"" else "text:p"
            val sb = StringBuilder("<$tag text:style-name=\"${paraStyle(p.style)}\">")
            p.style.listLabel?.let { sb.append(esc(it)).append("<text:tab/>") }
            for (i in p.inlines) when (i) {
                is TextRun -> sb.append("<text:span text:style-name=\"${charStyle(i.style)}\">${textOf(i.text)}</text:span>")
                is ImageRun -> {
                    val name = "Pictures/img${pics.size + 1}." + (if (i.mime.contains("png")) "png" else "jpg")
                    pics[name] = i.data
                    sb.append("<draw:frame text:anchor-type=\"as-char\" svg:width=\"${i.widthPt}pt\" svg:height=\"${i.heightPt}pt\"><draw:image xlink:href=\"$name\" xlink:type=\"simple\" xlink:show=\"embed\" xlink:actuate=\"onLoad\"/></draw:frame>")
                }
                is FieldRun -> sb.append(if (i.kind == "NUMPAGES") "<text:page-count/>" else "<text:page-number text:select-page=\"current\"/>")
            }
            sb.append("</${tag.substringBefore(' ')}>")
            return sb.toString()
        }
        var tableN = 0
        fun block(b: BlockNode): String = when (b) {
            is Paragraph -> para(b)
            is PageBreak -> "<text:p text:style-name=\"${paraStyle(ParaStyle(pageBreakBefore = true))}\"/>"
            is Table -> {
                tableN++
                val n = b.rows.maxOfOrNull { r -> r.cells.sumOf { it.span } } ?: 1
                val sb = StringBuilder("<table:table table:name=\"Table$tableN\"><table:table-column table:number-columns-repeated=\"$n\"/>")
                for (r in b.rows) {
                    sb.append("<table:table-row>")
                    for (c in r.cells) {
                        sb.append("<table:table-cell office:value-type=\"string\" table:style-name=\"Cell\"" + (if (c.span > 1) " table:number-columns-spanned=\"${c.span}\"" else "") + ">")
                        sb.append(c.blocks.joinToString("") { block(it) }.ifEmpty { "<text:p/>" })
                        sb.append("</table:table-cell>")
                        repeat(c.span - 1) { sb.append("<table:covered-table-cell/>") }
                    }
                    sb.append("</table:table-row>")
                }
                sb.append("</table:table>")
                sb.toString()
            }
        }
        val body = StringBuilder()
        for (sec in doc.sections) (sec.blocks + sec.fixedPages.flatMap { fp -> fp.frames.flatMap { it.blocks } }).forEach { body.append(block(it)) }
        val pg = doc.sections.firstOrNull()?.page ?: PageSetup()
        val nsAll = "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\" xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" " +
            "xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\" xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\" " +
            "xmlns:xlink=\"http://www.w3.org/1999/xlink\" xmlns:svg=\"urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0\" office:version=\"1.3\""
        val auto = StringBuilder("<style:style style:name=\"Cell\" style:family=\"table-cell\"><style:table-cell-properties fo:border=\"0.5pt solid #808080\" fo:padding=\"0.1cm\"/></style:style>")
        for ((k, name) in styles) {
            val (kind, props) = k.split('|', limit = 2)
            auto.append("<style:style style:name=\"$name\" style:family=\"${if (kind == "T") "text" else "paragraph"}\">$props</style:style>")
        }
        val files = LinkedHashMap<String, ByteArray>()
        files["mimetype"] = "application/vnd.oasis.opendocument.text".toByteArray()
        files["META-INF/manifest.xml"] = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\" manifest:version=\"1.3\">" +
            "<manifest:file-entry manifest:full-path=\"/\" manifest:media-type=\"application/vnd.oasis.opendocument.text\"/>" +
            "<manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/><manifest:file-entry manifest:full-path=\"styles.xml\" manifest:media-type=\"text/xml\"/>" +
            pics.keys.joinToString("") { "<manifest:file-entry manifest:full-path=\"$it\" manifest:media-type=\"image/${if (it.endsWith("png")) "png" else "jpeg"}\"/>" } +
            "</manifest:manifest>").toByteArray()
        files["content.xml"] = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-content $nsAll><office:automatic-styles>$auto</office:automatic-styles><office:body><office:text>$body</office:text></office:body></office:document-content>").toByteArray()
        files["styles.xml"] = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-styles $nsAll><office:automatic-styles><style:page-layout style:name=\"pm1\"><style:page-layout-properties fo:page-width=\"${pg.width}pt\" fo:page-height=\"${pg.height}pt\" " +
            "fo:margin-top=\"${pg.marginTop}pt\" fo:margin-bottom=\"${pg.marginBottom}pt\" fo:margin-left=\"${pg.marginLeft}pt\" fo:margin-right=\"${pg.marginRight}pt\"/></style:page-layout></office:automatic-styles>" +
            "<office:master-styles><style:master-page style:name=\"Standard\" style:page-layout-name=\"pm1\"/></office:master-styles></office:document-styles>").toByteArray()
        files.putAll(pics)
        ZipPackage(files).write(out)
    }
}
