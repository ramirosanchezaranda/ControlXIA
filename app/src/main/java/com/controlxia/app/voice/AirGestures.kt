package com.controlxia.app.voice

/**
 * Las señas con una mano frente a la cámara, cuadro a cuadro. Señas claras y
 * fáciles de distinguir (la palma se confundía con la mano que usa el teléfono):
 *
 * - ☝️ / 👇 índice arriba o abajo: el reel, Short o TikTok siguiente o anterior.
 * - 👉 / 👈 índice al costado: la foto o historia siguiente o anterior.
 * - ✊ puño sostenido: pausa o sigue el video (en un chat de WhatsApp, graba un video).
 * - 👍 me gusta, ✌️ te escucho (las dos piden que MediaPipe esté seguro).
 *
 * Para que el dedo cuente una sola vez: después de apuntar hay que volver a una
 * mano neutra (bajarla o relajar el dedo) antes de la próxima, hay una pausa
 * mínima entre señas, y el lado contrario no cuenta por un rato (al relajar el
 * dedo después de apuntar arriba, a veces se ve "abajo"). Qué hace cada seña
 * lo decide quien lo usa: el servicio de la cámara y la pantalla de prueba, así
 * se comportan igual. Sin Android: se prueba en la PC.
 */
class AirGestures {

    sealed interface Event {
        data class Swipe(val swipe: HandMotion.Swipe) : Event
        data class Sign(val sign: HandSigns.Sign) : Event
    }

    private val signs = HandSigns.Trigger(
        framesNeeded = 4,
        repeatable = emptySet(),
        framesFor = POINTING.associateWith { POINT_FRAMES } + (HandSigns.Sign.FIST to FIST_FRAMES),
    )
    private var lastPoint: HandSigns.Sign? = null
    private var lastPointAt = Long.MIN_VALUE / 2

    /** Después de apuntar hay que ver una mano neutra unos cuadros antes de la próxima. */
    private var needNeutral = false
    private var neutralFrames = 0

    /**
     * Un cuadro con la seña de la mano que más se ve (null sin mano).
     * [presented]: entera y a buena distancia (si no, no cuenta). [sure]:
     * MediaPipe está seguro de la seña (👍 y ✌️ lo piden).
     */
    fun feed(sign: HandSigns.Sign?, now: Long, presented: Boolean = true, sure: Boolean = true): Event? {
        var seen = sign?.takeIf { presented } ?: HandSigns.Sign.NONE
        if (seen in POINTING) {
            neutralFrames = 0
            val opposite = lastPoint?.let { OPPOSITE[it] } == seen && now - lastPointAt < RETURN_BLOCK_MS
            if (needNeutral || now - lastPointAt < POINT_GAP_MS || opposite) seen = HandSigns.Sign.NONE
        } else if (++neutralFrames >= NEUTRAL_FRAMES) {
            needNeutral = false
        }
        if ((seen == HandSigns.Sign.THUMB_UP || seen == HandSigns.Sign.VICTORY) && !sure) seen = HandSigns.Sign.NONE
        if (seen !in USED) seen = HandSigns.Sign.NONE
        val fired = signs.feed(seen, now) ?: return null
        val swipe = SWIPES[fired] ?: return Event.Sign(fired)
        lastPoint = fired
        lastPointAt = now
        needNeutral = true
        neutralFrames = 0
        return Event.Swipe(swipe)
    }

    fun reset() {
        signs.reset()
        needNeutral = false
        neutralFrames = 0
    }

    companion object {
        /** El puño se sostiene un poco más: es fácil cerrar la mano sin querer. */
        const val FIST_FRAMES = 6
        /** El dedo, medio segundo: así no cuenta el paso de una posición a otra. */
        const val POINT_FRAMES = 5
        private const val NEUTRAL_FRAMES = 3
        private const val POINT_GAP_MS = 1_000L
        private const val RETURN_BLOCK_MS = 2_000L

        private val POINTING = setOf(
            HandSigns.Sign.POINT_UP, HandSigns.Sign.POINT_DOWN, HandSigns.Sign.POINT_LEFT, HandSigns.Sign.POINT_RIGHT,
        )
        private val USED = POINTING + setOf(HandSigns.Sign.THUMB_UP, HandSigns.Sign.VICTORY, HandSigns.Sign.FIST)
        private val SWIPES = mapOf(
            HandSigns.Sign.POINT_UP to HandMotion.Swipe.UP,
            HandSigns.Sign.POINT_DOWN to HandMotion.Swipe.DOWN,
            HandSigns.Sign.POINT_LEFT to HandMotion.Swipe.LEFT,
            HandSigns.Sign.POINT_RIGHT to HandMotion.Swipe.RIGHT,
        )
        private val OPPOSITE = mapOf(
            HandSigns.Sign.POINT_UP to HandSigns.Sign.POINT_DOWN,
            HandSigns.Sign.POINT_DOWN to HandSigns.Sign.POINT_UP,
            HandSigns.Sign.POINT_LEFT to HandSigns.Sign.POINT_RIGHT,
            HandSigns.Sign.POINT_RIGHT to HandSigns.Sign.POINT_LEFT,
        )
    }
}
