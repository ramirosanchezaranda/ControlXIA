package com.controlxia.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.controlxia.app.ui.theme.Aura
import com.controlxia.app.ui.theme.rememberAnimationClock
import com.controlxia.app.ui.theme.rememberReduceMotion
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Esquinas de las tarjetas de vidrio. */
val GlassShape = RoundedCornerShape(24.dp)

/** El fondo que se desenfoca detrás de las barras de vidrio (lo pone la pantalla raíz). */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * Vidrio: relleno translúcido, borde con brillo (más claro arriba, como la
 * luz que pega en el canto) y una sombra difusa en modo claro. [strong] es
 * más opaco, para lo que va arriba de todo (barras, burbujas).
 */
fun Modifier.glass(
    shape: Shape = GlassShape,
    strong: Boolean = false,
    elevated: Boolean = true,
): Modifier = composed {
    val colors = Aura.colors
    val fill = if (strong) colors.glassStrong else colors.glass
    this
        .then(
            if (elevated && !colors.dark) {
                Modifier.shadow(
                    elevation = 14.dp,
                    shape = shape,
                    ambientColor = Color(0x0F1B2559),
                    spotColor = Color(0x261B2559),
                )
            } else {
                Modifier
            },
        )
        .clip(shape)
        .background(fill)
        .drawWithContent {
            drawContent()
            // Reflejo arriba: el vidrio agarra la luz por el borde superior.
            drawRect(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (colors.dark) 0.07f else 0.35f),
                    0.45f to Color.Transparent,
                ),
            )
        }
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(listOf(colors.glassEdgeTop, colors.glassEdgeBottom)),
            shape = shape,
        )
}

/**
 * Vidrio esmerilado de verdad: desenfoca lo que pasa por detrás (Android 12+;
 * antes, un vidrio más opaco). Para barras y paneles flotantes.
 */
fun Modifier.frostedGlass(shape: Shape = GlassShape): Modifier = composed {
    val state = LocalHazeState.current
    val colors = Aura.colors
    if (state == null) return@composed this.glass(shape, strong = true)
    val tint = if (colors.dark) Color(0x8C1C1D26) else Color(0x9EFFFFFF)
    this
        .then(
            if (!colors.dark) {
                Modifier.shadow(18.dp, shape, ambientColor = Color(0x141B2559), spotColor = Color(0x331B2559))
            } else {
                Modifier
            },
        )
        .clip(shape)
        .hazeChild(
            state = state,
            style = HazeStyle(
                backgroundColor = colors.background,
                tint = HazeTint(tint),
                blurRadius = 28.dp,
                noiseFactor = 0.06f,
            ),
        )
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(listOf(colors.glassEdgeTop, colors.glassEdgeBottom)),
            shape = shape,
        )
}

/**
 * El fondo de toda la app: luces de color grandes y difusas que se mueven
 * despacio (como el fondo "Liquid Glass"). Claras en modo claro, profundas en
 * oscuro. [energy] las aviva (por ejemplo, mientras escucha).
 */
@Composable
fun AuraBackground(modifier: Modifier = Modifier, energy: Float = 0f) {
    val colors = Aura.colors
    // Con las animaciones del sistema apagadas, las luces quedan quietas (y no se redibuja).
    // Las luces se mueven muy despacio: 20 cuadros por segundo alcanzan.
    val seconds by rememberAnimationClock(fps = 20, running = !rememberReduceMotion())
    val t = (seconds * (2 * Math.PI / CYCLE_S)).toFloat()
    Canvas(modifier.fillMaxSize()) {
        drawRect(colors.background)
        val big = max(size.width, size.height)
        GLOWS.forEachIndexed { i, glow ->
            val color = colors.glows[i % colors.glows.size]
            val phase = t + i * 1.7f
            val center = Offset(
                size.width * (glow.x + 0.10f * sin(phase)),
                size.height * (glow.y + 0.07f * cos(phase * 0.8f)),
            )
            val radius = big * glow.radius * (1f + 0.15f * energy)
            val alpha = (if (colors.dark) 0.55f else 0.85f) * (0.85f + 0.15f * energy)
            drawCircle(
                brush = Brush.radialGradient(
                    0f to color.copy(alpha = alpha),
                    0.55f to color.copy(alpha = alpha * 0.35f),
                    1f to color.copy(alpha = 0f),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
    }
}

private class Glow(val x: Float, val y: Float, val radius: Float)

/** Segundos de una vuelta completa de las luces del fondo. */
private const val CYCLE_S = 26.0

private val GLOWS = listOf(
    Glow(0.15f, 0.08f, 0.62f),
    Glow(0.92f, 0.30f, 0.55f),
    Glow(0.10f, 0.78f, 0.58f),
    Glow(0.85f, 0.95f, 0.60f),
)
