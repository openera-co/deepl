package app.carnet.core.dbg
import app.carnet.core.TestJson
import app.carnet.core.bench.FakeTranslator
import app.carnet.core.layout.*
import app.carnet.core.mt.TextTranslator
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
fun main(a: Array<String>) {
    @Suppress("UNCHECKED_CAST") val meta = TestJson.parse(File(a[0] + ".json").readText()) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") val lines = (meta["lines"] as List<Map<String, Any?>>).map { l ->
        val bb = (l["bbox"] as List<Double>).map { it.toFloat() }; val t = l["text"] as String
        TextLine(0, bb[0], bb[1], bb[2], bb[3], t, (bb[3] - bb[1]) * 0.98f, runs = listOf(StyledRun(t, Style())))
    }
    val img = javax.imageio.ImageIO.read(File(a[0]))
    val font = Font.createFont(Font.TRUETYPE_FONT, File("../app/src/main/assets/fonts/LiberationSans-Regular.ttf"))
    val g = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB).createGraphics()
    val m = TextMeasurer { t, s, _ -> g.getFontMetrics(font.deriveFont(s)).stringWidth(t).toFloat() }
    for (l in lines) println("L %.0f-%.0f y=%.0f-%.0f sz=%.1f '%s'".format(l.left, l.right, l.top, l.bottom, l.fontSize, l.text.take(60)))
    val bs = Layout.blocks(lines, 0.3f); Layout.assignRoom(bs, { img.height - 8f }, { img.width.toFloat() })
    for (b in bs) {
        val p = Layout.fit(b, TextTranslator(FakeTranslator).translate(b.text), m)
        println("B %.0f-%.0f y=%.0f-%.0f n=%d %s scale=%.2f room=%.0f maxR=%.0f '%s'".format(b.left, b.right, b.top, b.bottom, b.lines.size, b.align, p.fontSize / b.fontSize, b.maxBottom - b.top, b.maxRight, b.text.take(50)))
    }
}
