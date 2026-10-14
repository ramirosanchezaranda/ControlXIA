package com.controlxia.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import com.controlxia.app.ui.theme.rememberReduceMotion
import com.controlxia.app.voice.AssistantState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Qué está haciendo el asistente: cambia cómo se mueve la esfera. */
enum class OrbMood { IDLE, LISTENING, THINKING, SPEAKING }

/** La fase de la interacción en curso, como estado de la esfera. */
fun AssistantState.Phase.mood(): OrbMood = when (this) {
    AssistantState.Phase.IDLE -> OrbMood.IDLE
    AssistantState.Phase.LISTENING -> OrbMood.LISTENING
    AssistantState.Phase.HEARD -> OrbMood.THINKING
    AssistantState.Phase.REPLYING -> OrbMood.SPEAKING
}

/**
 * La esfera del asistente (como la de "Aura" o la de Siri): una bola de vidrio
 * con luces azules, violetas, rosas y celestes que giran adentro, un brillo
 * arriba a la izquierda, un borde de luz y un halo. En reposo respira despacio;
 * escuchando se agranda con tu voz ([level], 0 a 1); pensando gira rápido;
 * hablando late. Dibujada a mano, sin imágenes ni librerías.
 */
@Composable
fun AuraOrb(
    modifier: Modifier = Modifier,
    mood: OrbMood = OrbMood.IDLE,
    level: Float = 0f,
) {
    val reduce = rememberReduceMotion()
    val speed by animateFloatAsState(
        targetValue = when (mood) {
            OrbMood.IDLE -> 0.45f
            OrbMood.LISTENING -> 0.9f
            OrbMood.THINKING -> 2.2f
            OrbMood.SPEAKING -> 1.2f
        },
        animationSpec = spring(dampingRatio = 1f, stiffness = 40f),
        label = "orb-speed",
    )
    val energy by animateFloatAsState(
        targetValue = when (mood) {
            OrbMood.IDLE -> 0f
            OrbMood.LISTENING -> 0.45f + 0.55f * level.coerceIn(0f, 1f)
            OrbMood.THINKING -> 0.55f
            OrbMood.SPEAKING -> 0.5f
        },
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 260f),
        label = "orb-energy",
    )
    val currentSpeed by rememberUpdatedState(if (reduce) 0f else speed)
    // Reloj propio: así la velocidad cambia sin saltos.
    var time by remember { mutableFloatStateOf(0f) }
    var beat by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduce) {
        // Con las animaciones del sistema apagadas, la esfera queda quieta.
        if (reduce) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1e9f).coerceAtMost(0.05f)
                    time += dt * currentSpeed
                    beat += dt
                }
                last = now
            }
        }
    }
    val speaking = mood == OrbMood.SPEAKING

    Canvas(
        modifier
            .aspectRatio(1f)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
    ) {
        val c = center
        val breath = if (reduce) 0f else 0.5f + 0.5f * sin(beat * 2.2f)
        val pulse = if (speaking) 0.5f + 0.5f * sin(beat * 9f) else 0f
        val r = size.minDimension / 2f * 0.70f * (1f + 0.025f * breath + 0.09f * energy + 0.03f * pulse)

        // Halo de luz alrededor.
        // Sin pasarse del lienzo: si no, el halo se corta en un cuadrado.
        val haloR = minOf(r * (1.38f + 0.18f * energy), size.minDimension / 2f)
        drawCircle(
            brush = Brush.radialGradient(
                0f to HALO.copy(alpha = 0.38f + 0.3f * energy),
                0.55f to VIOLET.copy(alpha = 0.16f + 0.14f * energy),
                1f to Color.Transparent,
                center = c,
                radius = haloR,
            ),
            radius = haloR,
            center = c,
        )

        val sphere = Path().apply { addOval(Rect(c, r)) }
        clipPath(sphere) {
            // Cuerpo: azul profundo con la luz de arriba.
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color(0xFF7FB2FF),
                    0.45f to Color(0xFF2F5BFF),
                    1f to Color(0xFF1A1F8F),
                    center = c + Offset(-0.25f * r, -0.35f * r),
                    radius = r * 1.55f,
                ),
                radius = r,
                center = c,
            )
            // Luces de colores girando adentro.
            LOBES.forEachIndexed { i, lobe ->
                val angle = time * lobe.speed + lobe.phase
                val wobble = 1f + 0.18f * sin(time * 0.9f + i * 1.3f)
                val dist = r * lobe.distance * wobble * (1f - 0.25f * energy * (if (mood == OrbMood.THINKING) 1f else 0f))
                val p = c + Offset(cos(angle) * dist, sin(angle) * dist * 0.85f)
                val lr = r * lobe.size * (1f + 0.25f * energy)
                val white = lobe.color == Color.White
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to lobe.color.copy(alpha = lobe.alpha),
                        0.5f to lobe.color.copy(alpha = lobe.alpha * 0.55f),
                        1f to lobe.color.copy(alpha = 0f),
                        center = p,
                        radius = lr,
                    ),
                    radius = lr,
                    center = p,
                    // Los colores van encima tal cual (bien saturados); el blanco, como luz.
                    blendMode = if (white) BlendMode.Screen else BlendMode.SrcOver,
                )
            }
            // Una franja de seda de colores que gira al revés (como la de Siri):
            // un degradé circular que se desvanece hacia el centro y el borde.
            drawContext.canvas.saveLayer(Rect(c, r), Paint())
            rotate(degrees = -time * 55f, pivot = c) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        0f to VIOLET.copy(alpha = 0f),
                        0.22f to VIOLET.copy(alpha = 0.9f),
                        0.42f to PINK.copy(alpha = 0.85f),
                        0.58f to Color.Transparent,
                        0.78f to CYAN.copy(alpha = 0.8f),
                        1f to VIOLET.copy(alpha = 0f),
                        center = c,
                    ),
                    radius = r,
                    center = c,
                )
            }
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.3f to Color.Transparent,
                    0.62f to Color.Black.copy(alpha = 0.85f),
                    0.98f to Color.Transparent,
                    center = c,
                    radius = r,
                ),
                radius = r,
                center = c,
                blendMode = BlendMode.DstIn,
            )
            drawContext.canvas.restore()
            // Sombra abajo a la derecha: volumen.
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.7f to Color.Transparent,
                    1f to Color(0x66080B40),
                    center = c + Offset(-0.18f * r, -0.22f * r),
                    radius = r * 1.25f,
                ),
                radius = r,
                center = c,
            )
            // Brillo de vidrio arriba a la izquierda.
            val shine = c + Offset(-0.34f * r, -0.42f * r)
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.9f),
                    0.35f to Color.White.copy(alpha = 0.35f),
                    1f to Color.Transparent,
                    center = shine,
                    radius = r * 0.5f,
                ),
                radius = r * 0.5f,
                center = shine,
            )
        }
        // Borde de luz que gira.
        rotate(degrees = time * 40f, pivot = c) {
            drawCircle(
                brush = Brush.sweepGradient(
                    0f to Color.White.copy(alpha = 0.85f),
                    0.2f to Color.Transparent,
                    0.45f to CYAN.copy(alpha = 0.7f),
                    0.65f to Color.Transparent,
                    0.85f to PINK.copy(alpha = 0.6f),
                    1f to Color.White.copy(alpha = 0.85f),
                    center = c,
                ),
                radius = r - r * 0.012f,
                center = c,
                style = Stroke(width = r * 0.03f, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * La onda de la voz debajo de la esfera mientras escucha (como Siri): tres
 * curvas de colores que se encienden en el centro y crecen con [level].
 */
@Composable
fun VoiceWave(modifier: Modifier = Modifier, level: Float, active: Boolean = true) {
    val reduce = rememberReduceMotion()
    val amplitude by animateFloatAsState(
        targetValue = if (active) 0.18f + 0.82f * level.coerceIn(0f, 1f) else 0.04f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "wave-amp",
    )
    val shown by animateFloatAsState(if (active) 1f else 0f, spring(dampingRatio = 1f, stiffness = 120f), label = "wave-shown")
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduce) {
        if (reduce) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) time += ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
            }
        }
    }
    Canvas(modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen, alpha = shown)) {
        if (shown <= 0.01f) return@Canvas
        val mid = size.height / 2f
        val w = size.width
        WAVES.forEachIndexed { i, wave ->
            val path = Path()
            val steps = 90
            for (s in 0..steps) {
                val x = w * s / steps
                val u = (s.toFloat() / steps) * 2f - 1f // -1..1
                val envelope = exp(-u * u * 3.2f)
                val y = mid + sin(u * wave.frequency * PI.toFloat() + time * wave.speed + wave.phase) *
                    envelope * amplitude * size.height * 0.45f
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.5f to wave.color,
                    1f to Color.Transparent,
                ),
                style = Stroke(width = size.height * (0.035f - i * 0.006f), cap = StrokeCap.Round),
                blendMode = BlendMode.Screen,
            )
        }
    }
}

private class Lobe(
    val color: Color,
    val size: Float,
    val distance: Float,
    val speed: Float,
    val phase: Float,
    val alpha: Float,
)

private val CYAN = Color(0xFF52E5FF)
private val PINK = Color(0xFFFF5FB3)
private val VIOLET = Color(0xFF8C4BFF)
private val HALO = Color(0xFF5B8CFF)

private val LOBES = listOf(
    Lobe(Color(0xFF3D6BFF), 0.75f, 0.3f, -0.8f, 1.0f, 0.5f),
    Lobe(VIOLET, 0.8f, 0.5f, 1.0f, 0f, 0.9f),
    Lobe(PINK, 0.6f, 0.58f, -1.35f, 2.1f, 0.85f),
    Lobe(CYAN, 0.55f, 0.55f, 1.7f, 4.2f, 0.8f),
    Lobe(Color.White, 0.3f, 0.35f, 2.3f, 3.0f, 0.45f),
)

private class Wave(val color: Color, val frequency: Float, val speed: Float, val phase: Float)

private val WAVES = listOf(
    Wave(Color(0xFF4D8BFF), 2.2f, 5.0f, 0f),
    Wave(VIOLET, 3.0f, -6.5f, 1.4f),
    Wave(PINK, 1.6f, 7.5f, 2.8f),
)
