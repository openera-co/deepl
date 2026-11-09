package app.carnet.pdf

import android.content.Context
import java.io.InputStream

/** Bundled fonts (assets/fonts): Carlito ≈ Calibri, Liberation ≈ Arial/Times/Courier, DejaVu as fallback. */
class AndroidFonts(private val context: Context) : FontProvider {
    override fun open(family: String, bold: Boolean, italic: Boolean): InputStream {
        val style = when { bold && italic -> "BoldItalic"; bold -> "Bold"; italic -> "Italic"; else -> "Regular" }
        val name = when (family) {
            "calibri" -> "Carlito-$style"
            "serif" -> "LiberationSerif-$style"
            "mono" -> "LiberationMono-" + (if (bold) "Bold" else "Regular")
            else -> "LiberationSans-$style"
        }
        return context.assets.open("fonts/$name.ttf")
    }

    override fun fallback(): InputStream = context.assets.open("fonts/DejaVuSans.ttf")
}
