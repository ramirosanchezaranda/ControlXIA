package com.controlxia.app.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Lenguaje de movimiento de ControlXIA: minimalista, estilo GSAP.
 * Curva "expo.out" (arranque rápido, frenada larga) — la firma de GSAP —
 * con salida corta y entrada con leve desplazamiento vertical.
 */
val ExpoOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/** Transición estándar entre pantallas y pasos: fade + rise sutil. */
fun <T> screenTransition(): AnimatedContentTransitionScope<T>.() -> ContentTransform = {
    (
        fadeIn(tween(durationMillis = 420, delayMillis = 60, easing = ExpoOut)) +
            slideInVertically(
                tween(durationMillis = 420, delayMillis = 60, easing = ExpoOut)
            ) { fullHeight -> fullHeight / 20 }
        ).togetherWith(
            fadeOut(tween(durationMillis = 140, easing = FastOutLinearInEasing))
        )
}

/**
 * Reveal escalonado estilo GSAP: al aparecer, el bloque hace fade + rise con un
 * retraso proporcional a [index], de modo que las secciones entran en cascada.
 * Discreto por diseño (8dp de recorrido). Envolvé cada sección con esto.
 */
@Composable
fun Modifier.staggerReveal(
    index: Int,
    stepDelayMs: Int = 55,
    rise: Dp = 8.dp,
): Modifier {
    var shown by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(
            durationMillis = 460,
            delayMillis = index * stepDelayMs,
            easing = ExpoOut,
        ),
        label = "stagger-$index",
    )
    // Dispara la animación una sola vez, al entrar en composición.
    LaunchedEffect(Unit) { shown = true }
    val risePx = with(LocalDensity.current) { rise.toPx() }
    return this.graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * risePx
    }
}
