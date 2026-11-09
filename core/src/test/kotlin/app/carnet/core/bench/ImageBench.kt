package app.carnet.core.bench

import app.carnet.core.TestJson
import app.carnet.core.layout.Block
import app.carnet.core.layout.Layout
import app.carnet.core.layout.Style
import app.carnet.core.layout.StyledRun
import app.carnet.core.layout.TextLine
import app.carnet.core.layout.TextMeasurer
import app.carnet.core.mt.TextTranslator
import app.carnet.core.text.Segmenter
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Desktop twin of the app's photo overlay (Scanner.render): ground-truth line boxes from the
 * corpus stand in for ML Kit, then blocks are translated and redrawn exactly like on the phone.
 */
object ImageBench {
    private val font: Font by lazy { Font.createFont(Font.TRUETYPE_FONT, File(AssetFonts().let { "../app/src/main/assets/fonts/LiberationSans-Regular.ttf" }).takeIf { it.exists() } ?: File("app/src/main/assets/fonts/LiberationSans-Regular.ttf")) }

    fun run(src: File, out: File?, tr: TextTranslator): Pair<Long, List<Check>> {
        @Suppress("UNCHECKED_CAST") val meta = TestJson.parse(File(src.path + ".json").readText()) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val lines = (meta["lines"] as List<Map<String, Any?>>).map { l ->
            val bb = (l["bbox"] as List<Double>).map { it.toFloat() }
            val text = l["text"] as String
            TextLine(0, bb[0], bb[1], bb[2], bb[3], text, (bb[3] - bb[1]) * 0.78f, runs = listOf(StyledRun(text, Style())))
        }
        val img = ImageIO.read(src)
        val t0 = System.nanoTime()
        val blocks = Layout.blocks(lines)
        Layout.assignRoom(blocks, { img.height - 8f }, { img.width.toFloat() })
        val ling = blocks.filter { Segmenter.isLinguistic(it.text) }
        val en = tr.translateAll(ling.map { it.text })
        val map = HashMap<Block, String>(); ling.forEachIndexed { i, b -> map[b] = en[i] }
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val measurer = TextMeasurer { text, size, _ -> g.getFontMetrics(font.deriveFont(size)).stringWidth(text).toFloat() }
        val placedBoxes = ArrayList<Triple<Block, FloatArray, Float>>()
        for (b in blocks) {
            val text = map[b] ?: continue
            val bg = Color(img.getRGB(b.left.toInt().coerceIn(0, img.width - 1).let { maxOf(0, it - 4) }, b.top.toInt().coerceIn(0, img.height - 1)))
            g.color = bg
            for (l in b.lines) { val pad = l.height * 0.12f; g.fillRect((l.left - pad).toInt(), (l.top - pad).toInt(), (l.width + 2 * pad).toInt(), (l.height + 2 * pad).toInt()) }
            val p = Layout.fit(b, text, measurer)
            val lum = 0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue
            g.color = if (lum > 128) Color(25, 25, 25) else Color(240, 240, 235)
            g.font = font.deriveFont(p.fontSize)
            var minX = Float.MAX_VALUE; var maxX = 0f; var minY = Float.MAX_VALUE; var maxY = 0f
            for (pl in p.lines) for (s in pl.segments) {
                g.drawString(s.text, s.x, pl.baseline)
                minX = minOf(minX, s.x); maxX = maxOf(maxX, s.x + s.width); minY = minOf(minY, pl.baseline - p.fontSize * 0.8f); maxY = maxOf(maxY, pl.baseline + p.fontSize * 0.2f)
            }
            placedBoxes.add(Triple(b, floatArrayOf(minX, minY, maxX, maxY), p.fontSize / b.fontSize))
        }
        g.dispose()
        val ms = (System.nanoTime() - t0) / 1_000_000
        out?.let { it.parentFile.mkdirs(); ImageIO.write(img, "jpg", it) }
        val checks = ArrayList<Check>()
        val outside = placedBoxes.count { (_, r, _) -> r[0] < -1 || r[2] > img.width + 1 || r[1] < -1 || r[3] > img.height + 1 }
        checks += Check("text inside image", outside == 0, "$outside blocks")
        var overlaps = 0
        for (i in placedBoxes.indices) for (j in i + 1 until placedBoxes.size) {
            val a = placedBoxes[i].second; val b = placedBoxes[j].second
            val ix = minOf(a[2], b[2]) - maxOf(a[0], b[0]); val iy = minOf(a[3], b[3]) - maxOf(a[1], b[1])
            if (ix > 2 && iy > 2) overlaps++
        }
        checks += Check("no overlapping blocks", overlaps == 0, "$overlaps")
        val shrunk = placedBoxes.count { it.third < 0.75f }
        checks += Check("font kept ≥75%", shrunk == 0, "$shrunk of ${placedBoxes.size}")
        checks += Check("blocks found", blocks.isNotEmpty(), "${blocks.size}")
        return ms to checks
    }
}
