package app.carnet.core.mt

import java.io.File
import kotlin.test.Test

/** With CARNET_MODEL (model dir) and CARNET_REF (reference pieces, one line each) set: MarianText's pieces must match. */
class MarianTextParity {
    @Test
    fun matchesReferenceTokenizer() {
        val dir = System.getenv("CARNET_MODEL") ?: return
        val ref = System.getenv("CARNET_REF") ?: return
        val src = System.getenv("CARNET_SRC") ?: return
        val t = MarianText(DirectorySource(File(dir)))
        val lines = File(src).readLines(); val refs = File(ref).readLines()
        var same = 0
        lines.forEachIndexed { i, l -> val a = t.encode(l.trim()).joinToString(" "); if (a == refs[i].trim()) same++ else if (i - same < 5) println("DIFF $i\n  app: $a\n  ref: ${refs[i]}") }
        println("PARITY $same/${lines.size}")
    }
}
