package app.carnet.core.dbg
import app.carnet.core.layout.*
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
fun main() {
    val font = Font.createFont(Font.TRUETYPE_FONT, File("../app/src/main/assets/fonts/LiberationSans-Regular.ttf"))
    val g = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB).createGraphics()
    val m = TextMeasurer { t, s, _ -> g.getFontMetrics(font.deriveFont(s)).stringWidth(t).toFloat() }
    val l = TextLine(0, 105f, 113f, 700f, 180f, "Fiche de révision – Technologie", 65f, runs = listOf(StyledRun("Fiche de révision – Technologie", Style())))
    val other = TextLine(0, 105f, 220f, 980f, 260f, "corps du texte assez long pour la colonne", 38f, runs = listOf(StyledRun("x", Style())))
    val bs = Layout.blocks(listOf(l, other), 0.3f)
    Layout.assignRoom(bs, { 1430f }, { 1080f })
    val b = bs[0]
    println("block ${b.left}-${b.right} maxRight=${b.maxRight} size=${b.fontSize} lines=${b.lines.size}")
    val p = Layout.fit(b, "xehcifq xedq xnoisivérq – xeigolonhcetq xtrap xtrop", m)
    for (pl in p.lines) println("${pl.segments.map { it.x to it.width }} '${pl.text}' size=${p.fontSize} measured=${m.width(pl.text, p.fontSize, Style())}")
}
