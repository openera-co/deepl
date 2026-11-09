package app.carnet.core.doc

import app.carnet.core.convert.CharStyle
import app.carnet.core.convert.Cell
import app.carnet.core.convert.DocModel
import app.carnet.core.convert.DocxWriter
import app.carnet.core.convert.HAlign
import app.carnet.core.convert.ImageRun
import app.carnet.core.convert.Inline
import app.carnet.core.convert.ParaStyle
import app.carnet.core.convert.Paragraph
import app.carnet.core.convert.Row
import app.carnet.core.convert.Section
import app.carnet.core.convert.Table
import app.carnet.core.convert.TextRun
import app.carnet.core.mt.TextTranslator
import app.carnet.core.text.Segmenter
import org.apache.poi.hssf.usermodel.HSSFRichTextString
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.ss.usermodel.CellType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Old binary Office files (Word 97–2003 .doc, Excel 97–2003 .xls).
 * - .xls is translated in place: text cells (and sheet-level formatting) stay where they are.
 * - .doc is read into the document model (paragraph styles, bold/italic/underline/size/colour,
 *   lists, tables, images) and saved as a translated .docx.
 */
object LegacyOffice {

    fun translateXls(input: InputStream, output: OutputStream, translator: TextTranslator, progress: Progress = object : Progress {}): DocStats {
        val wb = HSSFWorkbook(input)
        val stats = DocStats()
        val cells = ArrayList<org.apache.poi.ss.usermodel.Cell>()
        for (s in 0 until wb.numberOfSheets) for (row in wb.getSheetAt(s)) for (c in row) {
            if (c.cellType == CellType.STRING && Segmenter.isLinguistic(c.stringCellValue)) cells.add(c)
        }
        stats.paragraphs = cells.size
        val out = translator.translateAll(cells.map { it.stringCellValue }, progress::cancelled) { d, t -> progress.update(d, t) }
        cells.forEachIndexed { i, c ->
            if (out[i] != c.stringCellValue) { c.setCellValue(HSSFRichTextString(out[i])); stats.translated++; stats.characters += out[i].length }
        }
        wb.write(output); wb.close()
        return stats
    }

    /** Reads a .doc into the conversion model. */
    fun readDoc(input: InputStream): DocModel {
        val doc = HWPFDocument(input)
        val range = doc.range
        val pics = doc.picturesTable
        val styles = doc.styleSheet
        val blocks = ArrayList<app.carnet.core.convert.BlockNode>()
        var i = 0
        while (i < range.numParagraphs()) {
            val p = range.getParagraph(i)
            if (p.isInTable) {
                val t = range.getTable(p)
                val rows = (0 until t.numRows()).map { r ->
                    val row = t.getRow(r)
                    Row((0 until row.numCells()).map { c ->
                        val cell = row.getCell(c)
                        Cell((0 until cell.numParagraphs()).map { k -> paragraph(cell.getParagraph(k), pics, doc, styles) })
                    }, header = r == 0)
                }
                val cols = rows.maxOfOrNull { it.cells.size } ?: 1
                blocks.add(Table(rows, List(cols) { 451f / cols }))
                i += t.numParagraphs()
                continue
            }
            blocks.add(paragraph(p, pics, doc, styles))
            i++
        }
        // Floating pictures (logos placed with "in front of text") aren't in the text flow: put them first.
        val inlinePics = blocks.flatMap { b -> (b as? Paragraph)?.inlines?.filterIsInstance<ImageRun>() ?: emptyList() }.map { it.data.size }.toSet()
        val floating = runCatching { pics.allPictures.filter { it.size > 200 && it.size !in inlinePics } }.getOrDefault(emptyList()).map { pic ->
            // Pictures outside the text flow may have no size record: use a modest default.
            val w = runCatching { pic.dxaGoal * pic.horizontalScalingFactor / 1000f / 20f }.getOrDefault(0f).takeIf { it > 0 } ?: 120f
            val h = runCatching { pic.dyaGoal * pic.verticalScalingFactor / 1000f / 20f }.getOrDefault(0f).takeIf { it > 0 } ?: 90f
            runCatching { Paragraph(listOf(ImageRun(pic.content, w.coerceAtMost(450f), h.coerceAtMost(600f), pic.mimeType ?: "image/png"))) }.getOrNull()
        }.filterNotNull()
        // Text boxes (side columns, address blocks) live in their own story.
        val boxes = runCatching { doc.mainTextboxRange }.getOrNull()?.let { r -> (0 until r.numParagraphs()).map { paragraph(r.getParagraph(it), pics, doc, styles) } }
            ?.filter { it.text.isNotBlank() } ?: emptyList()
        val stories = runCatching { org.apache.poi.hwpf.usermodel.HeaderStories(doc) }.getOrNull()
        fun story(t: String?) = t?.split('\r', '\n')?.map { it.replace(Regex("[\\u0000-\\u001F]"), "").trim() }?.filter { it.isNotEmpty() }
            ?.map { Paragraph(listOf(TextRun(it, CharStyle(size = 9f))), ParaStyle(align = HAlign.CENTER)) } ?: emptyList()
        val header = story(runCatching { stories?.firstHeader ?: stories?.oddHeader }.getOrNull())
        val footer = story(runCatching { stories?.firstFooter ?: stories?.oddFooter }.getOrNull())
        return DocModel(listOf(Section(blocks = floating + boxes + blocks, header = header, footer = footer)))
    }

    private fun paragraph(p: org.apache.poi.hwpf.usermodel.Paragraph, pics: org.apache.poi.hwpf.model.PicturesTable, doc: HWPFDocument, styles: org.apache.poi.hwpf.model.StyleSheet): Paragraph {
        val inlines = ArrayList<Inline>()
        for (k in 0 until p.numCharacterRuns()) {
            val r = p.getCharacterRun(k)
            if (pics.hasPicture(r)) {
                val pic = pics.extractPicture(r, false)
                val w = (pic.dxaGoal * pic.horizontalScalingFactor / 1000f / 20f).takeIf { it > 0 } ?: 200f
                val h = (pic.dyaGoal * pic.verticalScalingFactor / 1000f / 20f).takeIf { it > 0 } ?: 150f
                inlines.add(ImageRun(pic.content, w, h, pic.mimeType ?: "image/png"))
                continue
            }
            // Field codes, object anchors and control characters are dropped; text keeps its style.
            val text = r.text().replace(Regex("[\\u0001-\\u0008\\u000B\\u000C\\u000E-\\u001F\\r\\u0007]"), "")
            if (text.isEmpty()) continue
            val color = r.ico24.let { if (it == -1) 0xFF000000.toInt() else (0xFF shl 24) or ((it and 0xFF) shl 16) or (it and 0xFF00) or ((it shr 16) and 0xFF) }
            inlines.add(TextRun(text, CharStyle(bold = r.isBold, italic = r.isItalic, underline = r.underlineCode != 0, strike = r.isStrikeThrough,
                size = r.fontSize / 2f, color = color, font = r.fontName ?: "Calibri")))
        }
        val styleName = runCatching { styles.getStyleDescription(p.styleIndex.toInt())?.name }.getOrNull() ?: ""
        val heading = Regex("(?i)(heading|titre)\\s*(\\d)").find(styleName)?.groupValues?.get(2)?.toIntOrNull()
            ?: if (styleName.equals("Title", true) || styleName.equals("Titre", true)) 1 else 0
        val align = when (p.justification.toInt()) { 1 -> HAlign.CENTER; 2 -> HAlign.RIGHT; 3, 4 -> HAlign.JUSTIFY; else -> HAlign.LEFT }
        return Paragraph(inlines, ParaStyle(align = align, heading = heading, listLabel = if (p.isInList) "•" else null,
            indentLeft = p.indentFromLeft / 20f, firstLine = p.firstLineIndent / 20f,
            spaceBefore = p.spacingBefore / 20f, spaceAfter = (p.spacingAfter / 20f).takeIf { it > 0 } ?: 6f))
    }

    /** .doc → translated .docx: converted through the document model, then translated like any Word file. */
    fun translateDoc(input: InputStream, output: OutputStream, translator: TextTranslator, options: TranslateOptions, progress: Progress): DocStats {
        val model = readDoc(input)
        val docx = ByteArrayOutputStream().also { DocxWriter.write(model, it) }.toByteArray()
        return DocxTranslator().translate(ByteArrayInputStream(docx), output, translator, options, progress)
    }
}
