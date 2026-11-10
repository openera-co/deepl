package app.carnet.core.convert

import app.carnet.core.layout.Style

/** A simple, format-neutral document used for conversions (Word/ODT/PowerPoint/Excel/text → PDF/Word/ODT). */
data class CharStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val size: Float = 11f,
    val color: Int = 0xFF000000.toInt(),
    val highlight: Int? = null,
    val font: String = "Calibri",
    val vertAlign: Int = 0, // 1 superscript, -1 subscript
) {
    fun toStyle() = Style(bold, italic, color, font)
}

sealed class Inline
data class TextRun(val text: String, val style: CharStyle) : Inline()
data class ImageRun(val data: ByteArray, val widthPt: Float, val heightPt: Float, val mime: String = "image/png") : Inline()
/**
 * A picture placed on the page rather than in the line (Word "In front of / Square / Behind"): it
 * takes no room in the text. Position relative to [hRel]/[vRel] ("page", "margin", else the
 * paragraph), by alignment ("left"/"center"/"right", "top"/"center"/"bottom") or offset in points.
 */
data class FloatingImage(val image: ImageRun, val hRel: String, val hAlign: String?, val hOffset: Float, val vRel: String, val vAlign: String?, val vOffset: Float) : Inline()
data class FieldRun(val kind: String, val style: CharStyle) : Inline() // "PAGE", "NUMPAGES"

enum class HAlign { LEFT, CENTER, RIGHT, JUSTIFY }

data class ParaStyle(
    val align: HAlign = HAlign.LEFT,
    val spaceBefore: Float = 0f,
    val spaceAfter: Float = 6f,
    val lineSpacing: Float = 1.15f,
    val indentLeft: Float = 0f,
    val indentRight: Float = 0f,
    val firstLine: Float = 0f,
    val listLabel: String? = null,
    val heading: Int = 0,
    val pageBreakBefore: Boolean = false,
    val shading: Int? = null,
    val keepWithNext: Boolean = false,
    /** Word's paragraph style and "don't add space between paragraphs of the same style". */
    val styleId: String? = null,
    val contextual: Boolean = false,
    /** Font size of the paragraph mark: the height of an empty paragraph. */
    val markSize: Float? = null,
    /** Line under the paragraph (Word paragraph border): colour and width in points. */
    val borderBottom: Int? = null,
    val borderWidth: Float = 0.5f,
)

sealed class BlockNode
data class Paragraph(val inlines: List<Inline>, val style: ParaStyle = ParaStyle()) : BlockNode() {
    val text: String get() = inlines.filterIsInstance<TextRun>().joinToString("") { it.text }
}
data class Cell(val blocks: List<BlockNode>, val span: Int = 1, val shading: Int? = null)
data class Row(val cells: List<Cell>, val header: Boolean = false)
data class Table(val rows: List<Row>, val columnWidths: List<Float>, val borders: Boolean = true) : BlockNode()
object PageBreak : BlockNode()

/** Absolutely positioned content (PowerPoint shapes). */
data class Frame(val x: Float, val y: Float, val w: Float, val h: Float, val blocks: List<BlockNode>, val fill: Int? = null, val anchorMiddle: Boolean = false)

data class PageSetup(
    val width: Float = 595.3f, val height: Float = 841.9f,
    val marginTop: Float = 72f, val marginBottom: Float = 72f, val marginLeft: Float = 72f, val marginRight: Float = 72f,
    val headerY: Float = 36f, val footerY: Float = 36f,
)

data class Section(
    val page: PageSetup = PageSetup(),
    val blocks: List<BlockNode> = emptyList(),
    val header: List<BlockNode> = emptyList(),
    val footer: List<BlockNode> = emptyList(),
    /** Fixed-layout pages (slides): each list is drawn on its own page, no flow. */
    val fixedPages: List<FixedPage> = emptyList(),
)

data class FixedPage(val frames: List<Frame>, val background: Int? = null, val images: List<Pair<ImageRun, FloatArray>> = emptyList())

data class DocModel(val sections: List<Section>, val title: String? = null) {
    fun plainText(): String = sections.joinToString("\n") { s ->
        (s.blocks + s.fixedPages.flatMap { p -> p.frames.flatMap { it.blocks } }).joinToString("\n") { b -> blockText(b) }
    }

    private fun blockText(b: BlockNode): String = when (b) {
        is Paragraph -> b.text
        is Table -> b.rows.joinToString("\n") { r -> r.cells.joinToString("\t") { c -> c.blocks.joinToString(" ") { blockText(it) } } }
        else -> ""
    }
}
