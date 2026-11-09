package app.carnet.share

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.carnet.CarnetApp
import app.carnet.R
import app.carnet.app
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Serves installed models to another phone on the same Wi-Fi or hotspot. No internet needed.
 * GET /manifest, GET /file/<model>/<name> (Range supported). Every request needs the session token
 * shown in the QR code, so only someone who scanned it can download.
 */
object ShareServer {
    const val PORT = 8765
    data class State(val running: Boolean = false, val token: String = "", val addresses: List<String> = emptyList(), val sent: Long = 0, val clients: Int = 0)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun start(context: Context) {
        if (server != null) return
        val token = ByteArray(9).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val ss = ServerSocket(PORT)
        server = ss
        _state.value = State(true, token, localAddresses())
        pool.execute {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (_: SocketException) { break }
                pool.execute { handle(context.applicationContext, s, token) }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        _state.value = State()
    }

    /** "carnet://share?h=192.168.1.20:8765&t=token" */
    fun link(): String? = _state.value.takeIf { it.running }?.let { st -> st.addresses.firstOrNull()?.let { "carnet://share?h=$it:$PORT&t=${st.token}" } }

    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().map { it.hostAddress!! }.sortedBy { if (it.startsWith("192.168.")) 0 else 1 }
    }.getOrDefault(emptyList())

    private fun handle(context: Context, s: Socket, token: String) {
        s.use { sock ->
            sock.soTimeout = 30000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val headers = HashMap<String, String>()
            while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break; val i = l.indexOf(':'); if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim() }
            val out = sock.getOutputStream()
            val path = requestLine.split(' ').getOrNull(1) ?: "/"
            if (headers["x-carnet-token"] != token) return respond(out, 403, "text/plain", "Forbidden".toByteArray())
            _state.value = _state.value.copy(clients = _state.value.clients + 1)
            val mm = context.app.models
            when {
                path == "/manifest" -> respond(out, 200, "application/json", mm.manifest().toString().toByteArray())
                path.startsWith("/file/") -> {
                    val parts = path.removePrefix("/file/").split('/')
                    val id = parts.getOrNull(0) ?: return respond(out, 404, "text/plain", ByteArray(0))
                    val name = parts.getOrNull(1) ?: return respond(out, 404, "text/plain", ByteArray(0))
                    val spec = mm.catalog.firstOrNull { it.id == id } ?: return respond(out, 404, "text/plain", ByteArray(0))
                    if (spec.files.none { it.name == name } || !mm.isInstalled(id)) return respond(out, 404, "text/plain", ByteArray(0))
                    sendFile(out, mm.file(id, name), headers["range"])
                }
                else -> respond(out, 404, "text/plain", ByteArray(0))
            }
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        out.write("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body); out.flush()
    }

    private fun sendFile(out: OutputStream, f: File, range: String?) {
        val len = f.length()
        val start = range?.let { Regex("bytes=(\\d+)-").find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
        val code = if (start > 0) 206 else 200
        val header = StringBuilder("HTTP/1.1 $code ${if (code == 206) "Partial Content" else "OK"}\r\nContent-Type: application/octet-stream\r\nContent-Length: ${len - start}\r\n")
        if (code == 206) header.append("Content-Range: bytes $start-${len - 1}/$len\r\n")
        header.append("Connection: close\r\n\r\n")
        out.write(header.toString().toByteArray())
        f.inputStream().use { i ->
            i.skip(start)
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = i.read(buf); if (n < 0) break
                out.write(buf, 0, n)
                _state.value = _state.value.copy(sent = _state.value.sent + n)
            }
        }
        out.flush()
    }
}

/** Keeps the process alive (foreground) while sharing models or downloading them. */
class ShareService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { ShareServer.stop(); stopSelf(); return START_NOT_STICKY }
        val n: Notification = NotificationCompat.Builder(this, CarnetApp.CH_SHARE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Sharing models")
            .setContentText("Another phone can download your models")
            .setOngoing(true).build()
        ServiceCompat.startForeground(this, 2, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        ShareServer.start(this)
        return START_NOT_STICKY
    }

    override fun onDestroy() { ShareServer.stop(); super.onDestroy() }

    companion object {
        const val ACTION_STOP = "stop"
        fun start(c: Context) { androidx.core.content.ContextCompat.startForegroundService(c, Intent(c, ShareService::class.java)) }
        fun stop(c: Context) { c.startService(Intent(c, ShareService::class.java).setAction(ACTION_STOP)) }
    }
}
