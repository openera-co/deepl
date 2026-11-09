package app.carnet.core.dbg
import app.carnet.core.htr.HtrModel
import app.carnet.core.mt.DirectorySource
import java.io.File
import javax.imageio.ImageIO
/** DbgHtr <modelDir> <dir of line images> [n] [out.txt]: recognizes line images with the app's recognizer. */
fun main(a: Array<String>) {
    val m = HtrModel(DirectorySource(File(a[0])))
    val files = File(a[1]).listFiles { f -> f.extension == "png" }!!.sortedBy { it.name }.take(a.getOrNull(2)?.toInt() ?: 12)
    val lines = files.map { f -> val img = ImageIO.read(f); Triple(img.getRGB(0, 0, img.width, img.height, null, 0, img.width), img.width, img.height) }
    val t0 = System.nanoTime()
    val out = m.recognize(lines)
    val ms = (System.nanoTime() - t0) / 1_000_000
    if (a.size > 3) File(a[3]).writeText(out.joinToString("\n") + "\n") else out.take(4).forEach { println(it) }
    println("${files.size} lines in $ms ms (${ms / files.size} ms/line)")
    System.exit(0)
}
