package com.controlxia.app.voice

/**
 * Los gestos con una mano frente a la cámara, cuadro a cuadro:
 *
 * - ✋ palma abierta quieta y movida rápido: deslizar ([HandMotion]).
 * - ✋ palma abierta quieta [palmHoldMs] sin moverla: pausa o sigue.
 * - 👍 y ✌️ sostenidos unos cuadros, ✊ un poco más ([HandSigns.Trigger]): qué
 *   hace cada uno lo decide quien lo usa (✊ graba un video en WhatsApp).
 *
 * El índice apuntando no hace nada: al soltar el dedo después de apuntar para
 * arriba se veía "apuntando para abajo", y junto con el movimiento de la mano
 * pasaba dos veces. Lo usan el servicio de la cámara y la pantalla de prueba,
 * así se comportan igual. Sin Android: se prueba en la PC.
 */
class AirGestures(
    private val palmHoldMs: Long = PALM_HOLD_MS,
    private val motion: HandMotion = HandMotion(),
) {
    sealed interface Event {
        data class Swipe(val swipe: HandMotion.Swipe) : Event
        data class Sign(val sign: HandSigns.Sign) : Event
    }

    private val signs = HandSigns.Trigger(
        framesNeeded = 4,
        repeatable = emptySet(),
        framesFor = mapOf(HandSigns.Sign.FIST to FIST_FRAMES),
    )
    /** Ya pausó con esta palma quieta: no otra vez hasta que se mueva o se baje. */
    private var palmDone = false

    /** La palma está lista para deslizar. */
    val armed: Boolean get() = motion.armed

    /**
     * Un cuadro con la mano que más se ve: su [sign] y el centro [x], [y] (null
     * sin mano). [mirrored] como en [HandMotion.feed].
     */
    fun feed(sign: HandSigns.Sign?, x: Float?, y: Float?, now: Long, mirrored: Boolean = false): Event? {
        val open = sign == HandSigns.Sign.OPEN_PALM
        motion.feed(x, y, now, mirrored, open)?.let { swipe ->
            signs.reset()
            return Event.Swipe(swipe)
        }
        val still = motion.stillMs(now)
        if (still == 0L) palmDone = false
        if (open && still >= palmHoldMs && !palmDone) {
            palmDone = true
            signs.reset()
            return Event.Sign(HandSigns.Sign.OPEN_PALM)
        }
        val quick = sign?.takeIf { it in QUICK } ?: HandSigns.Sign.NONE
        return signs.feed(quick, now)?.let { Event.Sign(it) }
    }

    fun reset() {
        motion.reset()
        signs.reset()
        palmDone = false
    }

    companion object {
        /** La palma quieta este tiempo pausa (menos es para deslizar). */
        const val PALM_HOLD_MS = 2_000L
        /** El puño se sostiene casi un segundo: es fácil cerrar la mano sin querer. */
        const val FIST_FRAMES = 8
        private val QUICK = setOf(HandSigns.Sign.THUMB_UP, HandSigns.Sign.VICTORY, HandSigns.Sign.FIST)
    }
}
