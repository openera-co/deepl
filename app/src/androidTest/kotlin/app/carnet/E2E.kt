package app.carnet

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.carnet.convert.Converters
import app.carnet.models.ModelStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End to end, the way a student uses the app: downloads the models with the one button, then
 * translates every file in <files>/e2e/in through the same job the Document and Scan screens start.
 * Writes, per file, the translated file and the in-app preview PDF (what the result screen shows) to
 * e2e/out, and timings to e2e/out/report.json. Rendered and compared on the host (tools/e2e).
 */
@RunWith(AndroidJUnit4::class)
class E2E {
    private fun log(s: String) = android.util.Log.i("CarnetE2E", s)

    @Test
    fun run() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val app = ctx.app
        val quality = InstrumentationRegistry.getArguments().getString("quality") ?: "best"
        val dir = File(ctx.filesDir, "e2e"); val outDir = File(dir, "out").apply { mkdirs() }
        val report = JSONObject().put("quality", quality).put("cores", Runtime.getRuntime().availableProcessors())
        app.settings.update { it.copy(setupDone = true, model = quality, direction = "auto") }

        // 1. One-button model download.
        val t0 = System.currentTimeMillis()
        app.models.refreshCatalog()
        app.models.downloadRecommended(quality)
        val pack = app.models.pack(quality).map { it.id }
        withTimeout(40 * 60_000L) {
            while (true) {
                val st = app.models.status.value
                if (pack.all { st[it] is ModelStatus.Installed }) break
                pack.mapNotNull { id -> (st[id] as? ModelStatus.Failed)?.let { "$id: ${it.reason}" } }.firstOrNull()?.let { error("download failed $it") }
                delay(2000)
            }
        }
        report.put("download_s", (System.currentTimeMillis() - t0) / 1000.0).put("models", JSONArray(pack))
        log("models ready in ${(System.currentTimeMillis() - t0) / 1000}s")

        // 2. Model loading, each direction (what the student waits for before the first translation).
        val loads = JSONObject()
        for (d in listOf("fr-en", "en-fr")) {
            val t = System.currentTimeMillis()
            runCatching { app.engine.translate(listOf("Bonjour"), dir = d) }
            loads.put(d, (System.currentTimeMillis() - t) / 1000.0)
        }
        report.put("load_s", loads)
        // Text translation speed (warm).
        val sents = listOf("La photosynthèse est le processus par lequel les plantes fabriquent leur nourriture.",
            "Le théorème de Pythagore s'applique uniquement aux triangles rectangles.",
            "Les élèves doivent rendre leur devoir avant vendredi prochain.",
            "La Révolution française a commencé en 1789 avec la prise de la Bastille.")
        val tt = System.currentTimeMillis()
        val en = app.engine.translate(sents, dir = "fr-en")
        report.put("text_4_sentences_s", (System.currentTimeMillis() - tt) / 1000.0).put("text_sample", JSONArray(en))

        // 3. Every test file through the real job.
        val files = File(dir, "in").listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        val results = JSONArray()
        for (f in files) {
            log("translating ${f.name}")
            val r = JSONObject().put("file", f.name).put("bytes", f.length())
            val t = System.currentTimeMillis()
            try {
                val id = app.jobs.translateFile(Uri.fromFile(f), saveAsPdf = false)
                val stages = JSONArray(); var last = ""
                val flow = app.jobs.state(id)!!
                withTimeout(20 * 60_000L) {
                    while (true) {
                        val s = flow.value
                        if (s.stage != last) { stages.put("${(System.currentTimeMillis() - t) / 1000.0}s ${s.stage}"); last = s.stage }
                        if (s.finished || s.error != null) break
                        delay(200)
                    }
                }
                val s = flow.value
                r.put("seconds", (System.currentTimeMillis() - t) / 1000.0).put("stages", stages)
                if (s.error != null) r.put("error", s.error)
                val item = app.library.items.first().firstOrNull { it.id == id }
                val res = item?.resultName?.let { n: String -> File(app.library.dir(id), n) }
                if (res != null && res.exists()) {
                    val base = f.name.substringBeforeLast('.')
                    val out = File(outDir, "$base.translated.${res.extension}"); res.copyTo(out, true)
                    r.put("output", out.name).put("meta", JSONObject(item.meta as Map<*, *>))
                    // What the result screen shows: its preview PDFs of the original and the translation.
                    val tp = System.currentTimeMillis()
                    runCatching { if (res.extension != "pdf" && res.extension != "jpg") Converters.toPdf(ctx, res, File(outDir, "$base.preview-out.pdf")) }
                        .onFailure { r.put("preview_error", it.toString()) }
                    runCatching { if (f.extension.lowercase() !in setOf("pdf", "jpg", "jpeg", "png")) Converters.toPdf(ctx, f, File(outDir, "$base.preview-src.pdf")) }
                    r.put("preview_s", (System.currentTimeMillis() - tp) / 1000.0)
                    File(app.library.dir(id), "english.txt").takeIf { it.exists() }?.copyTo(File(outDir, "$base.english.txt"), true)
                    File(app.library.dir(id), "french.txt").takeIf { it.exists() }?.copyTo(File(outDir, "$base.french.txt"), true)
                }
            } catch (e: Throwable) {
                r.put("seconds", (System.currentTimeMillis() - t) / 1000.0).put("error", e.toString())
            }
            log("done ${f.name}: $r")
            results.put(r)
            File(outDir, "report.json").writeText(report.put("files", results).toString(1))
        }
        File(outDir, "report.json").writeText(report.put("files", results).toString(1))
    }
}
