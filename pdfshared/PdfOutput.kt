package app.carnet.pdf

// Shared source (see PdfTranslator.kt): Apache PDFBox on desktop, PdfBox-Android in the app.

import app.carnet.core.convert.BlockNode
import app.carnet.core.convert.CharStyle
import app.carnet.core.convert.DocModel
import app.carnet.core.convert.HAlign
import app.carnet.core.convert.ImageOp
import app.carnet.core.convert.LineOp
import app.carnet.core.convert.Paginator
import app.carnet.core.convert.ParaStyle
import app.carnet.core.convert.Paragraph
import app.carnet.core.convert.PageSetup
import app.carnet.core.convert.RectOp
import app.carnet.core.convert.RenderPage
import app.carnet.core.convert.Section
import app.carnet.core.convert.TextOp
import app.carnet.core.convert.TextRun
import app.carnet.core.layout.Layout
import app.carnet.core.layout.Style
import app.carnet.core.layout.TextMeasurer
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import java.io.OutputStream

/** Maps a document font name to one of the bundled families. */
fun familyOf(font: String): String {
    val n = font.lowercase()
    return when {
        n.contains("mono") || n.contains("courier") || n.contains("consol") -> "mono"
        n.contains("calibri") || n.contains("carlito") || n.isEmpty() -> "calibri"
        listOf("times", "serif", "georgia", "garamond", "cambria", "caladea", "book", "palatino", "roman").any { n.contains(it) } && !n.contains("sans") -> "serif"
        else -> "sans"
    }
}

/** Lays out and writes a [DocModel] as PDF with the bundled fonts. */
class PdfWriter(private val fonts: FontProvider) {
    fun write(model: DocModel, out: OutputStream) {
        PDDocument().use { doc ->
            val cache = HashMap<String, PDFont>()
            fun font(s: CharStyle): PDFont = cache.getOrPut("${familyOf(s.font)}${s.bold}${s.italic}") {
                fonts.open(familyOf(s.font), s.bold, s.italic).use { PDType0Font.load(doc, it, true) }
            }
            val fallback by lazy { fonts.fallback().use { PDType0Font.load(doc, it, true) } }
            val widthCache = HashMap<String, Float>()
            val measurer = TextMeasurer { text, size, st ->
                val key = "${familyOf(st.fontName)}${st.bold}${st.italic}|$text"
                val w1000 = widthCache.getOrPut(key) { safeWidth(font(CharStyle(bold = st.bold, italic = st.italic, font = st.fontName)), fallback, text) }
                w1000 * size / 1000f
            }
            val pages = Paginator(measurer).paginate(model)
            for (p in pages) render(doc, p, ::font, fallback)
            doc.documentInformation.title = model.title ?: ""
            doc.documentInformation.producer = "Carnet"
            doc.save(out)
        }
    }

    private fun safeWidth(f: PDFont, fallback: PDFont, text: String): Float {
        var w = 0f
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i); val s = String(Character.toChars(cp))
            w += try { f.getStringWidth(s) } catch (_: Exception) { try { fallback.getStringWidth(s) } catch (_: Exception) { 500f } }
            i += Character.charCount(cp)
        }
        return w
    }

    private fun render(doc: PDDocument, p: RenderPage, font: (CharStyle) -> PDFont, fallback: PDFont) {
        val page = PDPage(PDRectangle(p.width, p.height))
        doc.addPage(page)
        val images = HashMap<ByteArray, PDImageXObject?>()
        PDPageContentStream(doc, page).use { cs ->
            fun y(v: Float) = p.height - v
            for (op in p.ops) when (op) {
                is RectOp -> {
                    op.fill?.let { c -> cs.setNonStrokingColor((c shr 16) and 255, (c shr 8) and 255, c and 255); cs.addRect(op.x, y(op.y + op.h), op.w, op.h); cs.fill() }
                    op.stroke?.let { c -> cs.setStrokingColor((c shr 16) and 255, (c shr 8) and 255, c and 255); cs.setLineWidth(op.strokeWidth); cs.addRect(op.x, y(op.y + op.h), op.w, op.h); cs.stroke() }
                }
                is LineOp -> {
                    val c = op.color
                    cs.setStrokingColor((c shr 16) and 255, (c shr 8) and 255, c and 255); cs.setLineWidth(op.width)
                    cs.moveTo(op.x1, y(op.y1)); cs.lineTo(op.x2, y(op.y2)); cs.stroke()
                }
                is ImageOp -> {
                    if (!images.containsKey(op.image.data)) images[op.image.data] = try { PDImageXObject.createFromByteArray(doc, op.image.data, "img") } catch (_: Exception) { null }
                    val img = images[op.image.data] ?: continue
                    cs.drawImage(img, op.x, y(op.y + op.h), op.w, op.h)
                }
                is TextOp -> {
                    val c = op.style.color
                    cs.setNonStrokingColor((c shr 16) and 255, (c shr 8) and 255, c and 255)
                    PdfText.draw(cs, font(op.style), fallback, op.style.size, op.x, y(op.baseline), op.text)
                }
            }
        }
    }
}

/** Text drawing with per-character fallback for glyphs missing in the main font. */
object PdfText {
    private val ok = HashMap<Pair<PDFont, String>, Boolean>()
    private fun can(f: PDFont, s: String) = synchronized(ok) { ok.getOrPut(f to s) { try { f.encode(s); true } catch (_: Exception) { false } } }

    fun draw(cs: PDPageContentStream, f: PDFont, fallback: PDFont, size: Float, x: Float, y: Float, text: String) {
        cs.beginText(); cs.newLineAtOffset(x, y)
        var cur: PDFont? = null
        val run = StringBuilder()
        fun flush() { if (run.isNotEmpty()) { cs.setFont(cur!!, size); cs.showText(run.toString()); run.setLength(0) } }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i); val s = String(Character.toChars(cp))
            val use = if (can(f, s)) f else if (can(fallback, s)) fallback else null
            if (use != null) { if (use !== cur) { flush(); cur = use }; run.append(s) }
            i += Character.charCount(cp)
        }
        flush(); cs.endText()
    }
}

/** PDF → editable document: positioned lines become paragraphs with styles; large text becomes headings. */
object PdfToModel {
    fun read(file: File, fonts: FontProvider): DocModel {
        PDDocument.load(file).use { doc ->
            val t = PdfTranslator(fonts)
            val lines = t.extract(doc)
            val blocks = Layout.blocks(lines, rules = t::rulesOf)
            val sizes = blocks.map { it.fontSize }.sorted()
            val body = if (sizes.isEmpty()) 11f else sizes[sizes.size / 2]
            val sections = ArrayList<Section>()
            for (pi in 0 until doc.numberOfPages) {
                val pg = doc.getPage(pi).cropBox
                val pageBlocks = blocks.filter { it.page == pi }
                val out = ArrayList<BlockNode>()
                var prevBottom = 0f
                for (b in pageBlocks) {
                    val heading = when { b.fontSize > body * 1.6f -> 1; b.fontSize > body * 1.25f -> 2; b.bold && b.lines.size == 1 && b.fontSize >= body -> 3; else -> 0 }
                    val inlines = b.runs.map { r ->
                        val s: Style = b.styles[r.format]
                        TextRun(r.text, CharStyle(bold = s.bold, italic = s.italic, size = b.fontSize, color = s.color, font = cleanFont(s.fontName)))
                    }
                    val align = when (b.align) {
                        app.carnet.core.layout.Align.CENTER -> HAlign.CENTER; app.carnet.core.layout.Align.RIGHT -> HAlign.RIGHT
                        app.carnet.core.layout.Align.JUSTIFY -> HAlign.JUSTIFY; else -> HAlign.LEFT
                    }
                    val before = (b.top - prevBottom).coerceIn(0f, 30f)
                    out.add(Paragraph(inlines, ParaStyle(align = align, spaceBefore = if (out.isEmpty()) 0f else before, spaceAfter = 0f, lineSpacing = (b.lineHeight / (b.fontSize * 1.2f)).coerceIn(0.9f, 2f),
                        indentLeft = (b.left - 56f).coerceIn(0f, 200f) * 0f, heading = heading)))
                    prevBottom = b.bottom
                }
                sections.add(Section(PageSetup(pg.width, pg.height, 56f, 56f, (pageBlocks.minOfOrNull { it.left } ?: 56f).coerceIn(28f, 90f), 56f), out))
            }
            return DocModel(sections.ifEmpty { listOf(Section()) }, doc.documentInformation?.title)
        }
    }

    private fun cleanFont(n: String) = n.substringAfter('+').replace(Regex("(?i)[-,](bold|italic|oblique|regular|mt|ps).*"), "").ifEmpty { "Calibri" }
}

/** Page-level PDF tools. Page numbers are 0-based. */
object PdfTools {
    fun merge(inputs: List<File>, out: OutputStream) {
        PDDocument().use { dst ->
            val opened = inputs.map { PDDocument.load(it) }
            try {
                for (src in opened) for (p in src.pages) dst.importPage(p)
                dst.save(out)
            } finally { opened.forEach { it.close() } }
        }
    }

    /** Splits into single-page documents, or into the given page ranges. */
    fun split(input: File, ranges: List<IntRange>?, outFor: (Int) -> OutputStream) {
        PDDocument.load(input).use { src ->
            val rs = ranges ?: (0 until src.numberOfPages).map { it..it }
            rs.forEachIndexed { i, r ->
                PDDocument().use { d ->
                    for (p in r) if (p in 0 until src.numberOfPages) d.importPage(src.getPage(p))
                    outFor(i).use { d.save(it) }
                }
            }
        }
    }

    fun rotate(input: File, out: OutputStream, degrees: Int, pages: Set<Int>? = null) {
        PDDocument.load(input).use { d ->
            d.pages.forEachIndexed { i, p -> if (pages == null || i in pages) p.rotation = ((p.rotation + degrees) % 360 + 360) % 360 }
            d.save(out)
        }
    }

    fun reorder(input: File, out: OutputStream, order: List<Int>) {
        PDDocument.load(input).use { src ->
            PDDocument().use { d ->
                for (i in order) d.importPage(src.getPage(i))
                d.save(out)
            }
        }
    }

    fun pageNumbers(input: File, out: OutputStream, fonts: FontProvider, format: String = "%d / %d") {
        PDDocument.load(input).use { d ->
            val f = fonts.open("sans", false, false).use { PDType0Font.load(d, it, true) }
            val n = d.numberOfPages
            d.pages.forEachIndexed { i, p ->
                val box = p.cropBox
                val text = format.format(i + 1, n)
                val w = f.getStringWidth(text) / 1000f * 9f
                PDPageContentStream(d, p, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.setNonStrokingColor(90, 95, 106)
                    cs.beginText(); cs.setFont(f, 9f); cs.newLineAtOffset(box.lowerLeftX + (box.width - w) / 2, box.lowerLeftY + 20f); cs.showText(text); cs.endText()
                }
            }
            d.save(out)
        }
    }

    fun pageCount(input: File): Int = PDDocument.load(input).use { it.numberOfPages }

    /** All text of a PDF (for search and the Text tab). */
    fun text(input: File): String = PDDocument.load(input).use { org.apache.pdfbox.text.PDFTextStripper().getText(it) }
}
