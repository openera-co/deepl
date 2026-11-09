package app.carnet.core.bench

import app.carnet.core.doc.Documents
import app.carnet.core.doc.Progress
import app.carnet.core.mt.DirectorySource
import app.carnet.core.mt.MarianModel
import app.carnet.core.mt.MarianSentenceTranslator
import app.carnet.core.mt.SentenceTranslator
import app.carnet.core.mt.TextTranslator
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Test-loop harness.
 *   fidelity <corpusDir> <outDir> <fake|modelDir> [limitPerType] [types,comma]  → report.json + summary
 *   mt <modelDir> <src.fr> <out.en> [beam]                                         → hypotheses + timing
 */
fun main(args: Array<String>) {
    when (args.getOrNull(0)) {
        "fidelity" -> fidelity(args.drop(1))
        "mt" -> mt(args.drop(1))
        else -> error("usage: fidelity|mt …")
    }
}

private fun engineFor(spec: String): SentenceTranslator =
    if (spec == "fake") FakeTranslator else MarianSentenceTranslator(MarianModel(DirectorySource(File(spec))))

private fun mt(a: List<String>) {
    val model = MarianModel(DirectorySource(File(a[0])))
    val beam = a.getOrNull(3)?.toInt() ?: 1
    val tr = TextTranslator(MarianSentenceTranslator(model, beam))
    val src = File(a[1]).readLines()
    // Warm-up so the first-call cost isn't measured.
    tr.translate("Bonjour à tous.")
    val t0 = System.nanoTime()
    val out = tr.translateAll(src)
    val sec = (System.nanoTime() - t0) / 1e9
    File(a[2]).writeText(out.joinToString("\n") + "\n")
    println("translated ${src.size} lines in ${"%.1f".format(sec)} s → ${"%.1f".format(src.size / sec)} lines/s, ${"%.0f".format(src.sumOf { it.length } / sec)} chars/s")
}

private fun fidelity(a: List<String>) {
    val corpus = File(a[0]); val outDir = File(a[1]); val spec = a[2]
    val limit = a.getOrNull(3)?.toInt() ?: Int.MAX_VALUE
    val types = a.getOrNull(4)?.split(",")?.toSet() ?: setOf("docx", "pptx", "xlsx", "odt", "txt", "md", "csv", "srt")
    val fake = spec == "fake"
    val engine = engineFor(spec)
    val threads = if (fake) Runtime.getRuntime().availableProcessors() else 1
    val pool = Executors.newFixedThreadPool(threads)
    val files = types.flatMap { t -> (File(corpus, t).listFiles { f -> f.extension == t }?.sortedBy { it.name } ?: emptyList()).take(limit) }
    data class Res(val file: String, val ms: Long, val chars: Int, val checks: List<Check>, val error: String?)
    val futures = ArrayList<Future<Res>>()
    for (f in files) futures += pool.submit<Res> {
        val tr = TextTranslator(engine)
        val out = File(outDir, f.parentFile.name + "/" + f.name).also { it.parentFile.mkdirs() }
        val t0 = System.nanoTime()
        try {
            if (f.extension == "pdf") {
                val ms = PdfBench.translate(f, out, tr)
                return@submit Res(f.path, ms, f.length().toInt() / 50, PdfBench.check(f, out, fake), null)
            }
            val stats = out.outputStream().use { o -> f.inputStream().use { i -> Documents.translate(f.name, i, o, tr, progress = object : Progress {}) } }
            val ms = (System.nanoTime() - t0) / 1_000_000
            Res(f.path, ms, stats.characters, Fidelity.check(f, out, fake), null)
        } catch (e: Throwable) {
            Res(f.path, (System.nanoTime() - t0) / 1_000_000, 0, emptyList(), e.toString() + " @ " + e.stackTrace.take(3).joinToString(" < "))
        }
    }
    val results = futures.map { it.get() }
    pool.shutdown()
    // Summary per type and per check.
    val byType = results.groupBy { File(it.file).extension }
    val sb = StringBuilder()
    for ((t, rs) in byType.toSortedMap()) {
        val errors = rs.count { it.error != null }
        val failedDocs = rs.count { r -> r.error != null || r.checks.any { !it.ok } }
        val ms = rs.map { it.ms }.sorted()
        sb.append("$t: ${rs.size} docs, ${rs.size - failedDocs} fully ok, $errors crashed, median ${ms[ms.size / 2]} ms, p95 ${ms[(ms.size * 95) / 100]} ms, ${"%.0f".format(rs.sumOf { it.chars } * 1000.0 / rs.sumOf { it.ms }.coerceAtLeast(1))} chars/s\n")
        val failsByCheck = rs.flatMap { r -> r.checks.filter { !it.ok }.map { it.name.replace(Regex("\\d+"), "#") to "${File(r.file).name}: ${it.detail}" } }.groupBy({ it.first }, { it.second })
        for ((name, ex) in failsByCheck.toList().sortedByDescending { it.second.size }) sb.append("   ✗ $name: ${ex.size}  e.g. ${ex.first()}\n")
        rs.filter { it.error != null }.take(3).forEach { sb.append("   ! ${File(it.file).name}: ${it.error}\n") }
    }
    println(sb)
    File(outDir, "summary.txt").writeText(sb.toString())
    File(outDir, "report.tsv").writeText(results.joinToString("\n") { r -> "${r.file}\t${r.ms}\t${r.chars}\t${r.error ?: ""}\t" + r.checks.filter { !it.ok }.joinToString("; ") { it.name + "=" + it.detail } })
    if (engine is MarianSentenceTranslator) System.exit(0)
}
