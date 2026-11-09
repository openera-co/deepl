package app.carnet

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import app.carnet.data.AppSettings
import app.carnet.ui.AppNav
import app.carnet.ui.CarnetTheme

/** Something another app handed us: shared text, a file to translate, or a link from a QR code. */
sealed class Incoming {
    data class Text(val text: String) : Incoming()
    data class Files(val uris: List<Uri>) : Incoming()
}

class MainActivity : ComponentActivity() {
    val incoming = mutableStateOf<Incoming?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            val settings by app.settings.flow.collectAsState(initial = null as AppSettings?)
            val s = settings ?: return@setContent
            CarnetTheme(s.theme) { AppNav(s, incoming) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    @Suppress("DEPRECATION")
    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.let { incoming.value = Incoming.Text(it.toString()) }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)
                when {
                    uri != null -> incoming.value = Incoming.Files(listOf(uri))
                    text != null -> incoming.value = Incoming.Text(text)
                }
            }
            Intent.ACTION_VIEW -> intent.data?.let { incoming.value = Incoming.Files(listOf(it)) }
        }
    }
}
