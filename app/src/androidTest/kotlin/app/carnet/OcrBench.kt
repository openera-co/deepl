package app.carnet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * OCR benchmark on a device/emulator: reads every image in <app files>/ocrbench/in with the app's
 * real recognizer (ML Kit, same code as the Scan screen) and writes the recognized lines and timing
 * to ocrbench/out.json. Scored on the host against the ground truth (tools/ocr/score.py).
 * Argument "variant" selects preprocessing to compare.
 */
@RunWith(AndroidJUnit4::class)
class OcrBench {
    @Test
    fun run() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val variant = args.getString("variant") ?: "default"
        val dir = File(ctx.getExternalFilesDir(null), "ocrbench")
        val files = File(dir, "in").listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }?.sortedBy { it.name } ?: emptyList()
        val out = JSONArray()
        val scanner = ctx.app.scanner
        for (f in files) {
            val bmp = BitmapFactory.decodeFile(f.path)?.copy(Bitmap.Config.ARGB_8888, false) ?: continue
            val t0 = System.nanoTime()
            val prepared = app.carnet.ocr.Preprocess.apply(bmp, variant)
            val (lines, _) = scanner.lines(prepared)
            val ms = (System.nanoTime() - t0) / 1_000_000
            out.put(JSONObject().put("file", f.name).put("ms", ms).put("lines", JSONArray(lines.map { it.text })))
        }
        File(dir, "out-$variant.json").writeText(JSONObject().put("variant", variant).put("results", out).toString())
    }
}
