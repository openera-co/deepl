package app.carnet.core.dbg
import app.carnet.core.TestJson
import app.carnet.core.bench.FakeTranslator
import app.carnet.core.layout.*
import app.carnet.core.mt.TextTranslator
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
fun main() {
    @Suppress("UNCHECKED_CAST") val meta = TestJson.parse(File("/opt/data/corpus/jpg/jpg_00004.jpg.json").readText()) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") val lines = (meta["lines"] as List<Map<String, Any?>>).map { l ->
        val bb = (l["bbox"] as List<Double>).map { it.toFloat() }; val t = l["text"] as String
        TextLine(0, bb[0], bb[1], bb[2], bb[3], t, (bb[3] - bb[1]) * 0.98f, runs = listOf(StyledRun(t, Style())))
    }
    val font = Font.createFont(Font.TRUETYPE_FONT, File("../app/src/main/assets/fonts/LiberationSans-Regular.ttf"))
    val g = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB).createGraphics()
    val m = TextMeasurer { t, s, _ -> g.getFontMetrics(font.deriveFont(s)).stringWidth(t).toFloat() }
    val bs = Layout.blocks(lines, 0.3f); Layout.assignRoom(bs, { 1432f }, { 1080f })
    val b = bs[0]
    val tr = TextTranslator(FakeTranslator).translate(b.text)
    println("block '${b.text}' ${b.left}-${b.right} top=${b.top} maxRight=${b.maxRight} minLeft=${b.minLeft} align=${b.align} size=${b.fontSize} room=${b.maxBottom - b.top} hard=${b.hardBottom}")
    val p = Layout.fit(b, tr, m)
    for (pl in p.lines) println("${pl.segments.map { it.x to it.width }} '${pl.text}' size=${p.fontSize}")
}
