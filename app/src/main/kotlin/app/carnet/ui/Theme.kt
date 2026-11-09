package app.carnet.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class Palette(
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val line: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val accentInk: Color,
    val accentSoft: Color,
    val good: Color,
    val goodSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val warnSoft: Color,
    val warnInk: Color,
    val dark: Boolean,
)

val LightPalette = Palette(
    bg = Color(0xFFF6F6F3), surface = Color.White, surfaceAlt = Color(0xFFEEEEEA), line = Color(0xFFE4E4DF),
    ink = Color(0xFF15171C), muted = Color(0xFF5A5F6A), faint = Color(0xFF8A8F98),
    accent = Color(0xFF1F4FD1), accentInk = Color.White, accentSoft = Color(0xFFE8EEFC),
    good = Color(0xFF1C5E33), goodSoft = Color(0xFFE7F2EA), danger = Color(0xFFB42318), dangerSoft = Color(0xFFFBE9E7),
    warnSoft = Color(0xFFFFF4D6), warnInk = Color(0xFF8A5A00), dark = false,
)

val DarkPalette = Palette(
    bg = Color(0xFF101114), surface = Color(0xFF1A1C21), surfaceAlt = Color(0xFF23262C), line = Color(0xFF2C2F36),
    ink = Color(0xFFEDEEF0), muted = Color(0xFFA6AAB2), faint = Color(0xFF7D828B),
    accent = Color(0xFF6C8DF0), accentInk = Color(0xFF0B1330), accentSoft = Color(0xFF1E2A4F),
    good = Color(0xFF7FD39A), goodSoft = Color(0xFF15301F), danger = Color(0xFFFF8A80), dangerSoft = Color(0xFF3A1C1A),
    warnSoft = Color(0xFF3A3018), warnInk = Color(0xFFF2C46B), dark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

object Type {
    val title = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
    val screenTitle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)
    val section = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
    val bodyStrong = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val small = TextStyle(fontSize = 13.sp)
    val tiny = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
    val input = TextStyle(fontSize = 19.sp, lineHeight = 27.sp)
    val output = TextStyle(fontSize = 19.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun CarnetTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) darkColorScheme(primary = p.accent, background = p.bg, surface = p.surface, onSurface = p.ink, onBackground = p.ink)
    else lightColorScheme(primary = p.accent, background = p.bg, surface = p.surface, onSurface = p.ink, onBackground = p.ink)
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

val pal: Palette @Composable get() = LocalPalette.current
