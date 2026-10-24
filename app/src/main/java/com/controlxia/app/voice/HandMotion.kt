package com.controlxia.app.voice

import kotlin.math.abs

/**
 * Deslizar en el aire con la palma abierta, como los gestos aéreos de los
 * teléfonos: abrís la mano frente a la cámara, la dejás quieta un instante
 * (queda "lista") y la movés rápido hacia arriba, abajo o a un costado. Es
 * como deslizar el dedo en la pantalla: mano para arriba = el contenido sube
 * (el reel siguiente).
 *
 * Para que cada movimiento cuente una sola vez:
 * - Solo se pone lista con la **palma abierta** y quieta: levantar o bajar la
 *   mano, o moverla después de otra seña (👍, ✌️), no cuenta.
 * - El movimiento tiene que ser **rápido**: si tarda mucho en recorrer la
 *   distancia, era la mano acomodándose.
 * - **La vuelta no cuenta**: después de deslizar para arriba, bajar la mano
 *   (para el otro lado) no hace nada por un rato. Para otro deslizamiento hay
 *   que dejar la mano quieta de nuevo.
 *
 * Si en pleno movimiento la mano se sale de la imagen, también cuenta (es lo
 * más común al deslizar rápido). Las posiciones van de 0 a 1 en la imagen
 * derecha (y crece hacia abajo). Sin Android: se prueba en la PC.
 */
class HandMotion(
    private val armMs: Long = 300,
    /** Después de deslizar, quieta un poco más para volver a estar lista. */
    private val rearmMs: Long = 500,
    private val stillTolerance: Float = 0.05f,
    private val swipeDistance: Float = 0.18f,
    /** Desde que empieza a moverse hasta recorrer la distancia, como mucho. */
    private val maxSwipeMs: Long = 450,
    private val idleRearmMs: Long = 1_500,
    private val dominant: Float = 1.5f,
    private val cooldownMs: Long = 900,
    /** Cuánto tiempo después de deslizar no cuenta para el lado contrario (la vuelta de la mano). */
    private val returnBlockMs: Long = 2_000,
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
    private var movingSince = NOT_MOVING
    private var missed = 0
    private var lastSwipe: Swipe? = null
    private var lastSwipeAt = Long.MIN_VALUE / 2

    /** La mano está lista para deslizar. */
    var armed = false
        private set

    /**
     * Un cuadro: [x], [y] el centro de la mano (null si no hay mano) y [open]
     * si es una palma abierta (solo hace falta para ponerse lista: en pleno
     * movimiento la imagen sale movida y la seña no siempre se reconoce).
     * [mirrored]: la imagen está espejada como una selfie (la de la cámara
     * frontal sin procesar no lo está: tu derecha aparece a la izquierda).
     */
    fun feed(x: Float?, y: Float?, now: Long, mirrored: Boolean = false, open: Boolean = true): Swipe? {
        if (x == null || y == null) {
            // Se fue la mano: si ya venía deslizando rápido, cuenta.
            val exit = if (armed && movingSince != NOT_MOVING && now - movingSince <= maxSwipeMs) {
                direction(lastX - anchorX, lastY - anchorY, EXIT_FRACTION, mirrored)
            } else {
                null
            }
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
            val moved = abs(x - anchorX) > stillTolerance || abs(y - anchorY) > stillTolerance
            if (moved || !open) {
                // Todavía se está acomodando (o no es la palma): empieza a contar de nuevo desde acá.
                stillSince = now
                anchorX = x
                anchorY = y
            } else if (now - stillSince >= restNeeded(now) && now - lastSwipeAt >= cooldownMs) {
                armed = true
                armedAt = now
                movingSince = NOT_MOVING
                anchorX = x
                anchorY = y
            }
            return null
        }
        val dx = x - anchorX
        val dy = y - anchorY
        if (movingSince == NOT_MOVING && (abs(dx) > stillTolerance || abs(dy) > stillTolerance)) movingSince = now
        direction(dx, dy, 1f, mirrored)?.let { return fire(it, now) }
        if (movingSince != NOT_MOVING && now - movingSince > maxSwipeMs) {
            // Se movió despacio: era la mano acomodándose. Hay que volver a dejarla quieta.
            disarm(now, x, y)
            return null
        }
        // Lista pero quieta mucho rato: se vuelve a tomar la posición actual.
        if (now - armedAt > idleRearmMs && abs(dx) < stillTolerance && abs(dy) < stillTolerance) {
            armedAt = now
            movingSince = NOT_MOVING
            anchorX = x
            anchorY = y
        }
        return null
    }

    /** Cuánto hace que la mano está quieta (0 si se está moviendo o no hay mano). */
    fun stillMs(now: Long): Long = if (!tracking || movingSince != NOT_MOVING) 0 else now - stillSince

    fun reset() {
        tracking = false
        armed = false
        movingSince = NOT_MOVING
        missed = 0
    }

    /** Recién deslizó: hace falta estar quieta un poco más para volver a estar lista. */
    private fun restNeeded(now: Long): Long = if (now - lastSwipeAt < returnBlockMs) rearmMs else armMs

    private fun disarm(now: Long, x: Float, y: Float) {
        armed = false
        movingSince = NOT_MOVING
        stillSince = now
        anchorX = x
        anchorY = y
    }

    private fun fire(swipe: Swipe, now: Long): Swipe? {
        val returning = lastSwipe?.let { opposite(it) == swipe } == true && now - lastSwipeAt < returnBlockMs
        // Para otro deslizamiento hay que volver a dejar la mano quieta.
        armed = false
        tracking = false
        movingSince = NOT_MOVING
        if (returning) return null
        lastSwipe = swipe
        lastSwipeAt = now
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
        const val NOT_MOVING = Long.MIN_VALUE

        fun opposite(swipe: Swipe): Swipe = when (swipe) {
            Swipe.UP -> Swipe.DOWN
            Swipe.DOWN -> Swipe.UP
            Swipe.LEFT -> Swipe.RIGHT
            Swipe.RIGHT -> Swipe.LEFT
        }
    }
}
