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
            TextLine(0, bb[0], bb[1], bb[2], bb[3], text, (bb[3] - bb[1]) * 0.98f, runs = listOf(StyledRun(text, Style())))
        }
        val img = ImageIO.read(src)
        val t0 = System.nanoTime()
        val blocks = Layout.blocks(lines, sizeTolerance = 0.3f)
        Layout.assignRoom(blocks, { img.height - 8f }, { img.width.toFloat() })
        val ling = blocks.filter { Segmenter.isLinguistic(it.text) }
        val en = tr.translateAll(ling.map { it.text })
        val map = HashMap<Block, String>(); ling.forEachIndexed { i, b -> map[b] = en[i] }
        val w = img.width; val h = img.height
        val px = img.getRGB(0, 0, w, h, null, 0, w)
        val orig = px.copyOf()
        val colours = HashMap<Block, Int>()
        for (b in blocks) {
            if (map[b] == null) continue
            val bg = app.carnet.core.layout.Inpaint.background(orig, w, h, b.left.toInt() - 4, b.top.toInt() - 4, b.right.toInt() + 4, b.bottom.toInt() + 4)
            colours[b] = app.carnet.core.layout.Inpaint.ink(orig, w, h, b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt(), bg)
            for (l in b.lines) {
                val pad = (l.height * 0.15f).toInt() + 1
                val lbg = app.carnet.core.layout.Inpaint.background(orig, w, h, l.left.toInt() - pad, l.top.toInt() - pad, l.right.toInt() + pad, l.bottom.toInt() + pad)
                app.carnet.core.layout.Inpaint.eraseInk(px, w, h, l.left.toInt() - pad, l.top.toInt() - pad, l.right.toInt() + pad, l.bottom.toInt() + pad, lbg, dilate = maxOf(2, (l.height / 12).toInt()))
            }
        }
        img.setRGB(0, 0, w, h, px, 0, w)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val measurer = TextMeasurer { text, size, _ -> g.getFontMetrics(font.deriveFont(size)).stringWidth(text).toFloat() }
        val placedBoxes = ArrayList<Triple<Block, FloatArray, Float>>()
        for (b in blocks) {
            val text = map[b] ?: continue
            val p = Layout.fit(b, text, measurer)
            g.color = Color(colours[b] ?: 0xFF111111.toInt(), true)
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
        val out = placedBoxes.filter { (_, r, _) -> r[0] < -1 || r[2] > img.width + 1 || r[1] < -1 || r[3] > img.height + 1 }
        checks += Check("text inside image", out.isEmpty(), out.joinToString { (b, r, sc) -> "box=${r.map { it.toInt() }} img=${img.width}x${img.height} scale=${"%.2f".format(sc)} lines=${b.lines.size} '${b.text.take(30)}'" })
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
