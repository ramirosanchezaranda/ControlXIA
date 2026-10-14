package com.controlxia.app.ui.theme

import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Movimiento al estilo de Apple: resortes en vez de curvas fijas (todo
 * frena con naturalidad y se puede interrumpir), duraciones de 200 a 500 ms,
 * "push" lateral entre pantallas con un poco de paralaje, y botones que se
 * achican apenas al tocarlos. Si el teléfono tiene las animaciones apagadas
 * (accesibilidad), todo aparece sin moverse.
 */
object AuraMotion {
    /** Resorte sin rebote para pantallas y paneles (como UIKit por defecto). */
    fun <T> smooth() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f)

    /** Resorte con un rebote apenas visible (soltar un botón, indicadores). */
    fun <T> snappy() = spring<T>(dampingRatio = 0.78f, stiffness = 520f)

    /** Rebote suave y lento (la esfera, apariciones). */
    fun <T> gentle() = spring<T>(dampingRatio = 0.72f, stiffness = 180f)

    /** Curva "ease out" de iOS para lo que no usa resortes. */
    val EaseOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
}

// Nombre de antes (lo usan pantallas viejas).
val ExpoOut = AuraMotion.EaseOut

/**
 * true si el usuario apagó las animaciones del sistema o tiene prendido el
 * ahorro de batería: ahí las animaciones continuas (esfera, fondo) se quedan
 * quietas.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        val noAnimations = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
        val powerSave = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        noAnimations || powerSave
    }
}

/**
 * Un reloj en segundos para animaciones continuas, que avanza a lo sumo
 * [fps] veces por segundo (no hace falta redibujar 60 o 120 veces por
 * segundo una luz que se mueve despacio) y solo mientras [running]. [speed]
 * lo acelera o frena sin saltos.
 */
@Composable
fun rememberAnimationClock(fps: Int, running: Boolean = true, speed: () -> Float = { 1f }): State<Float> {
    val time = remember { mutableFloatStateOf(0f) }
    val currentSpeed by rememberUpdatedState(speed)
    val currentFps by rememberUpdatedState(fps)
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (last != 0L) time.floatValue += ((now - last) / 1e9f).coerceAtMost(0.1f) * currentSpeed()
            last = now
            delay((1_000L / currentFps.coerceIn(1, 120)) - 4L)
        }
    }
    return time
}

/** Entre pasos (onboarding): aparece subiendo apenas, el anterior se desvanece rápido. */
fun <T> screenTransition(): AnimatedContentTransitionScope<T>.() -> ContentTransform = {
    (
        fadeIn(tween(durationMillis = 320, delayMillis = 40, easing = AuraMotion.EaseOut)) +
            slideInVertically(AuraMotion.smooth()) { it / 16 } +
            scaleIn(AuraMotion.smooth(), initialScale = 0.98f)
        ).togetherWith(fadeOut(tween(durationMillis = 140)))
}

/**
 * "Push" de iOS: la pantalla nueva entra desde la derecha y la anterior se
 * corre un poco a la izquierda y se oscurece (paralaje). Al volver, al revés.
 */
fun <T> pushTransition(forward: Boolean): AnimatedContentTransitionScope<T>.() -> ContentTransform = {
    val spec = spring<IntOffset>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 380f)
    if (forward) {
        (slideInHorizontally(spec) { it } + fadeIn(tween(220)))
            .togetherWith(slideOutHorizontally(spec) { -it / 4 } + fadeOut(tween(260)))
    } else {
        (slideInHorizontally(spec) { -it / 4 } + fadeIn(tween(220)))
            .togetherWith(slideOutHorizontally(spec) { it } + fadeOut(tween(260)))
    }
}

/** Cambio de pestaña: fundido corto con un mínimo de escala. */
fun <T> tabTransition(): AnimatedContentTransitionScope<T>.() -> ContentTransform = {
    (fadeIn(tween(240, easing = AuraMotion.EaseOut)) + scaleIn(AuraMotion.smooth(), initialScale = 0.985f))
        .togetherWith(fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.995f))
}

/**
 * Aparición escalonada: cada bloque entra subiendo y aclarándose con un
 * resorte, un poco después que el anterior ([index]).
 */
@Composable
fun Modifier.staggerReveal(
    index: Int,
    stepDelayMs: Int = 45,
    rise: Dp = 14.dp,
): Modifier {
    val reduce = rememberReduceMotion()
    val progress = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            delay(index.coerceAtMost(8) * stepDelayMs.toLong())
            progress.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 210f))
        }
    }
    val risePx = with(LocalDensity.current) { rise.toPx() }
    return this.graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * risePx
        val s = 0.97f + 0.03f * p
        scaleX = s
        scaleY = s
    }
}

/**
 * Toque al estilo iOS: se achica a [pressedScale] mientras está apretado y
 * vuelve con un rebote suave al soltar, con un "tic" háptico liviano. Sin la
 * onda de Material.
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    pressedScale: Float = 0.96f,
    haptic: Boolean = true,
    role: Role? = Role.Button,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = if (pressed) spring(dampingRatio = 1f, stiffness = 1500f) else spring(dampingRatio = 0.55f, stiffness = 420f),
        label = "press",
    )
    val feedback = LocalHapticFeedback.current
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
        ) {
            if (haptic) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
}
