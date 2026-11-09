package app.carnet.core

import app.carnet.core.mt.DirectorySource
import app.carnet.core.mt.MarianModel
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/** Compares the Kotlin tokenizer and decoder with the Python reference (transformers + optimum). */
class ModelParityTest {
    private val modelDir = File(System.getProperty("carnet.model").ifEmpty { "/opt/models/opus-fr-en" })
    private val refDir = File(System.getProperty("carnet.data").ifEmpty { "/opt/data/ref/base" })

    @Test
    fun tokenizerMatchesReference() {
        assumeTrue(File(refDir, "tok.jsonl").exists() && modelDir.exists())
        val model = MarianModel(DirectorySource(modelDir))
        var total = 0; var same = 0
        val bad = ArrayList<String>()
        File(refDir, "tok.jsonl").forEachLine { line ->
            @Suppress("UNCHECKED_CAST") val o = TestJson.parse(line) as Map<String, Any?>
            val ref = (o["p"] as List<String>).dropLast(1) // drop </s>
            val mine = model.sourceTokens(o["s"] as String).take(255)
            total++
            if (mine == ref) same++ else if (bad.size < 8) bad.add("${o["s"]}\n  ref=$ref\n  got=$mine")
        }
        println("tokenizer parity: $same / $total")
        bad.forEach { println(it) }
        assertTrue(same >= total * 0.995, "tokenizer parity $same/$total")
        model.close()
    }

    @Test
    fun translationMatchesReference() {
        assumeTrue(File(refDir, "mt.jsonl").exists() && modelDir.exists())
        val fp32 = System.getProperty("carnet.fp32") == "true"
        val model = if (fp32) MarianModel(DirectorySource(File(modelDir, "fp32").also { File(modelDir, "model.json").copyTo(File(it, "model.json"), true); listOf("source.pieces", "target.pieces", "vocab.txt").forEach { n -> File(modelDir, n).copyTo(File(it, n), true) } }), encoderFile = "encoder_model.onnx", decoderFile = "decoder_model_merged.onnx")
            else MarianModel(DirectorySource(modelDir))
        val rows = File(refDir, "mt.jsonl").readLines().map { @Suppress("UNCHECKED_CAST") (TestJson.parse(it) as Map<String, Any?>) }
        val src = rows.map { it["s"] as String }
        for ((beam, key) in listOf(1 to "greedy", 4 to "beam4")) {
            val t0 = System.nanoTime()
            val out = src.map { model.translate(listOf(it), beam = beam)[0] }
            val ms = (System.nanoTime() - t0) / 1e6
            val same = out.indices.count { out[it] == rows[it][key] }
            println("$key parity: $same / ${out.size} in ${"%.0f".format(ms)} ms (${"%.1f".format(ms / out.size)} ms/sentence)")
            out.indices.filter { out[it] != rows[it][key] }.take(5).forEach { println("  src=${src[it]}\n  ref=${rows[it][key]}\n  got=${out[it]}") }
            assertTrue(same >= out.size * (if (fp32) 0.9 else 0.7), "$key parity $same/${out.size}")
        }
        model.close()
    }
}
