package app.carnet.core.dbg
import app.carnet.core.mt.*
import java.io.File
/** DbgMt <modelDir> <file with one French text per line>: prints school-mode and plain translations. */
fun main(a: Array<String>) {
    val m = MarianModel(DirectorySource(File(a[0])))
    val school = TextTranslator(MarianSentenceTranslator(m)); val plain = TextTranslator(MarianSentenceTranslator(m), schoolMode = false)
    if (a.getOrNull(2) == "batch") {
        val ls = File(a[1]).readLines().filter { it.isNotBlank() }
        school.translateAll(ls).forEachIndexed { i, t -> println("FR: ${ls[i]}\n B: $t") }
        System.exit(0)
    }
    for (l in File(a[1]).readLines().filter { it.isNotBlank() }) {
        println("FR: $l\n S: ${school.translate(l)}\n P: ${plain.translate(l)}")
    }
    System.exit(0)
}
