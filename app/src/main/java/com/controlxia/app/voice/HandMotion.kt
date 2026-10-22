package com.controlxia.app.voice

import kotlin.math.abs

/**
 * Deslizar en el aire, como los gestos aéreos de los teléfonos: dejás la mano
 * quieta frente a la cámara un instante (queda "lista") y la movés rápido
 * hacia arriba, abajo o a un costado. Es como deslizar el dedo en la pantalla:
 * mano para arriba = el contenido sube (el reel siguiente).
 *
 * Que la mano tenga que quedarse quieta antes evita que cuente el movimiento de
 * levantarla o bajarla. Si en pleno movimiento la mano se sale de la imagen,
 * también cuenta (es lo más común al deslizar rápido). Las posiciones van de 0
 * a 1 en la imagen derecha (y crece hacia abajo). Sin Android: se prueba en la PC.
 */
class HandMotion(
    private val armMs: Long = 300,
    private val stillTolerance: Float = 0.05f,
    private val swipeDistance: Float = 0.18f,
    private val idleRearmMs: Long = 1_500,
    private val dominant: Float = 1.5f,
    private val cooldownMs: Long = 900,
) {
    /** La dirección del deslizamiento, como la ve el usuario. */
    enum class Swipe { UP, DOWN, LEFT, RIGHT }

    private var stillSince = 0L
    private var anchorX = 0f
    private var anchorY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var tracking = false
    private var armedAt = 0L
    private var missed = 0
    private var lastSwipeAt = Long.MIN_VALUE / 2

    /** La mano está lista para deslizar. */
    var armed = false
        private set

    /**
     * Un cuadro: [x], [y] el centro de la mano (null si no hay mano).
     * [mirrored]: la imagen está espejada como una selfie (la de la cámara
     * frontal sin procesar no lo está: tu derecha aparece a la izquierda).
     */
    fun feed(x: Float?, y: Float?, now: Long, mirrored: Boolean = false): Swipe? {
        if (x == null || y == null) {
            // Se fue la mano: si ya venía deslizando, cuenta.
            val exit = if (armed) direction(lastX - anchorX, lastY - anchorY, EXIT_FRACTION, mirrored) else null
            if (exit != null && now - lastSwipeAt >= cooldownMs) return fire(exit, now)
            if (++missed >= MAX_MISSED) reset()
            return null
        }
        missed = 0
        lastX = x
        lastY = y
        if (!tracking) {
            tracking = true
            stillSince = now
            anchorX = x
            anchorY = y
            return null
        }
        if (!armed) {
            if (abs(x - anchorX) > stillTolerance || abs(y - anchorY) > stillTolerance) {
                // Todavía se está acomodando: empieza a contar de nuevo desde acá.
                stillSince = now
                anchorX = x
                anchorY = y
            } else if (now - stillSince >= armMs && now - lastSwipeAt >= cooldownMs) {
                armed = true
                armedAt = now
                anchorX = x
                anchorY = y
            }
            return null
        }
        direction(x - anchorX, y - anchorY, 1f, mirrored)?.let { return fire(it, now) }
        // Lista pero quieta mucho rato: se vuelve a tomar la posición actual.
        if (now - armedAt > idleRearmMs && abs(x - anchorX) < stillTolerance && abs(y - anchorY) < stillTolerance) {
            armedAt = now
            anchorX = x
            anchorY = y
        }
        return null
    }

    fun reset() {
        tracking = false
        armed = false
        missed = 0
    }

    private fun fire(swipe: Swipe, now: Long): Swipe {
        lastSwipeAt = now
        // Para otro deslizamiento hay que volver a dejar la mano quieta.
        armed = false
        tracking = false
        return swipe
    }

    private fun direction(dx: Float, dy: Float, fraction: Float, mirrored: Boolean): Swipe? {
        val need = swipeDistance * fraction
        return when {
            abs(dy) >= need && abs(dy) >= abs(dx) * dominant -> if (dy < 0) Swipe.UP else Swipe.DOWN
            abs(dx) >= need && abs(dx) >= abs(dy) * dominant -> {
                // Sin espejar, ir hacia la derecha de la imagen es ir hacia tu izquierda.
                val towardImageRight = dx > 0
                if (towardImageRight == mirrored) Swipe.RIGHT else Swipe.LEFT
            }
            else -> null
        }
    }

    private companion object {
        /** Al salirse de la imagen alcanza con haber recorrido una parte. */
        const val EXIT_FRACTION = 0.6f
        const val MAX_MISSED = 2
    }
}
