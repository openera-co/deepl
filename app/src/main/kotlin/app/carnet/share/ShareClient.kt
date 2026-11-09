package app.carnet.share

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Receiving side of model sharing. */
object ShareClient {
    data class Remote(val base: String, val token: String)
    data class RemoteModel(val id: String, val title: String, val size: Long)

    fun parse(link: String): Remote? {
        val h = Regex("[?&]h=([^&]+)").find(link)?.groupValues?.get(1) ?: return null
        val t = Regex("[?&]t=([0-9a-f]+)").find(link)?.groupValues?.get(1) ?: return null
        return Remote("http://$h", t)
    }

    suspend fun manifest(r: Remote): List<RemoteModel> = withContext(Dispatchers.IO) {
        val c = URL("${r.base}/manifest").openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 10000
        c.setRequestProperty("X-Carnet-Token", r.token)
        if (c.responseCode != 200) throw IllegalStateException("The other phone refused (${c.responseCode})")
        val j = JSONObject(c.inputStream.bufferedReader().readText())
        val arr = j.getJSONArray("models")
        (0 until arr.length()).map { arr.getJSONObject(it).let { m -> RemoteModel(m.getString("id"), m.getString("title"), m.getLong("size")) } }
    }

    fun qr(text: String, size: Int = 720, dark: Int = Color.BLACK, light: Int = Color.WHITE): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { i -> if (m[i % size, i / size]) dark else light }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}
