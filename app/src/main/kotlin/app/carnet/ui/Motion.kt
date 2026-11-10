package app.carnet.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * A live sound wave: three layered sine curves that travel sideways and swell with the voice
 * ([level] 0..1). Flat and still when [active] is false.
 */
@Composable
fun SoundWave(level: Float, active: Boolean, modifier: Modifier = Modifier, color: Color = pal.accent) {
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "phase")
    val amp by animateFloatAsState(if (active) (0.12f + level * 0.88f).coerceIn(0f, 1f) else 0.03f, spring(dampingRatio = 0.55f, stiffness = 180f), label = "amp")
    Canvas(modifier) {
        val w = size.width; val mid = size.height / 2; val maxA = size.height * 0.46f
        val layers = listOf(Triple(1.0f, 1.6f, 1f), Triple(0.62f, 2.3f, 0.45f), Triple(0.38f, 3.1f, 0.22f))
        for ((k, layer) in layers.withIndex()) {
            val (scale, freq, alpha) = layer
            val path = Path()
            var x = 0f
            while (x <= w) {
                val p = x / w
                // Taper to the edges so the wave floats in the middle.
                val envelope = sin(p * PI).toFloat().let { it * it }
                val y = mid + sin(p * freq * 2 * PI + phase * (1 + k * 0.35f) + k).toFloat() * maxA * amp * scale * envelope
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += 3f
            }
            drawPath(path, color.copy(alpha = alpha), style = Stroke(width = (3.5f - k).dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

/** Soft rings breathing out from behind a button (idle record button, listening state). */
@Composable
fun PulseRings(color: Color, size: Dp, modifier: Modifier = Modifier, rings: Int = 2, content: @Composable () -> Unit) {
    val t = rememberInfiniteTransition(label = "rings")
    Box(modifier.size(size * 1.9f), contentAlignment = Alignment.Center) {
        for (i in 0 until rings) {
            val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, delayMillis = i * 1200, easing = LinearEasing), RepeatMode.Restart), label = "r$i")
            Canvas(Modifier.fillMaxSize()) {
                val r = size.toPx() / 2 * (1f + p * 0.85f)
                drawCircle(color.copy(alpha = (1f - p) * 0.22f), radius = r, center = Offset(this.size.width / 2, this.size.height / 2))
            }
        }
        content()
    }
}

/** Items slide up and fade in, one after another, the first time a screen shows. */
@Composable
fun Modifier.enter(index: Int): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(360, delayMillis = index * 45)) }
    return graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 28f }
}
