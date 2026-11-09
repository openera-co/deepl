package app.carnet.core.dbg
import app.carnet.core.convert.*
import java.io.File
fun main(a: Array<String>) {
    val m = Readers.read(a[0], File(a[0]).inputStream())
    for (s in m.sections) { println("page ${s.page.width}x${s.page.height} fixed=${s.fixedPages.size}")
        s.fixedPages.take(3).forEachIndexed { i, p -> println(" slide $i frames=${p.frames.size} imgs=${p.images.size}"); p.frames.forEach { f -> println("   frame ${f.x},${f.y} ${f.w}x${f.h} blocks=${f.blocks.size} ${f.blocks.filterIsInstance<Paragraph>().joinToString("|") { it.text.take(30) }}") } } }
}
