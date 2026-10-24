package com.controlxia.app.voice

import com.controlxia.app.voice.AirGestures.Event
import com.controlxia.app.voice.HandSigns.Sign
import org.junit.Assert.assertEquals
import org.junit.Test

/** Los gestos con una mano juntos: deslizar con la palma, palma quieta, 👍 y ✌️. */
class AirGesturesTest {

    private data class Frame(val sign: Sign?, val x: Float = 0.5f, val y: Float = 0.5f)

    /** Cuadros cada 100 ms; devuelve los eventos con su cuadro. */
    private fun run(frames: List<Frame>): List<Pair<Int, Event>> {
        val air = AirGestures()
        return frames.mapIndexedNotNull { i, f ->
            air.feed(f.sign, f.sign?.let { f.x }, f.sign?.let { f.y }, i * 100L)?.let { i to it }
        }
    }

    private fun palm(n: Int, y: Float = 0.5f) = List(n) { Frame(Sign.OPEN_PALM, y = y) }

    @Test
    fun palmSwipeUpIsOneSwipe() {
        val frames = palm(5, 0.6f) + Frame(Sign.OPEN_PALM, y = 0.4f) + palm(8, 0.4f) + Frame(Sign.OPEN_PALM, y = 0.6f)
        assertEquals(listOf(5 to Event.Swipe(HandMotion.Swipe.UP)), run(frames))
    }

    @Test
    fun palmHeldStillPausesOnce() {
        assertEquals(listOf(20 to Event.Sign(Sign.OPEN_PALM)), run(palm(40)))
    }

    @Test
    fun pointingDoesNothing() {
        // Apuntar arriba y soltar el dedo (se ve "apuntando abajo") no hace nada.
        val frames = List(6) { Frame(Sign.POINT_UP, y = 0.5f) } + List(3) { Frame(Sign.POINT_DOWN, y = 0.6f) }
        assertEquals(emptyList<Pair<Int, Event>>(), run(frames))
    }

    @Test
    fun thumbUpThenLoweringTheHandIsOnlyALike() {
        val frames = List(6) { Frame(Sign.THUMB_UP, y = 0.4f) } +
            listOf(Frame(Sign.THUMB_UP, y = 0.5f), Frame(Sign.NONE, y = 0.7f), Frame(null))
        assertEquals(listOf(3 to Event.Sign(Sign.THUMB_UP)), run(frames))
    }

    @Test
    fun fistNeedsAlmostASecondAndOnlyOnceUntilTheHandGoesDown() {
        // Empieza a grabar con un puño; el mismo puño sostenido no corta, sí uno nuevo.
        val fist = List(12) { Frame(Sign.FIST) }
        val frames = fist + listOf(Frame(null), Frame(null)) + fist
        assertEquals(
            listOf(7 to Event.Sign(Sign.FIST), 21 to Event.Sign(Sign.FIST)),
            run(frames),
        )
    }
}
