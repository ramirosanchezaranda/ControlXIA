package com.controlxia.app.voice

import com.controlxia.app.voice.AirGestures.Event
import com.controlxia.app.voice.HandMotion.Swipe
import com.controlxia.app.voice.HandSigns.Sign
import org.junit.Assert.assertEquals
import org.junit.Test

/** Las señas con una mano: el dedo una sola vez, el puño, 👍 y ✌️. */
class AirGesturesTest {

    /** Cuadros cada 100 ms; devuelve los eventos con su cuadro. */
    private fun run(frames: List<Sign?>, presented: Boolean = true, sure: Boolean = true): List<Pair<Int, Event>> {
        val air = AirGestures()
        return frames.mapIndexedNotNull { i, sign -> air.feed(sign, i * 100L, presented, sure)?.let { i to it } }
    }

    private fun many(sign: Sign?, n: Int) = List(n) { sign }

    @Test
    fun pointingUpIsTheNextReelOnce() {
        // Medio segundo apuntando: una vez, aunque se mantenga.
        assertEquals(listOf(4 to Event.Swipe(Swipe.UP)), run(many(Sign.POINT_UP, 20)))
    }

    @Test
    fun relaxingTheFingerAfterwardsIsNotTheOtherWay() {
        // Arriba, y al soltar el dedo se ve "abajo" un momento: no es "anterior".
        val frames = many(Sign.POINT_UP, 6) + many(Sign.POINT_DOWN, 6) + many(null, 5)
        assertEquals(listOf(4 to Event.Swipe(Swipe.UP)), run(frames))
    }

    @Test
    fun twoReelsInARowNeedTheHandToRelaxBetween() {
        // Arriba, bajar la mano un segundo y arriba otra vez: dos reels.
        val frames = many(Sign.POINT_UP, 6) + many(null, 10) + many(Sign.POINT_UP, 6)
        assertEquals(listOf(4 to Event.Swipe(Swipe.UP), 20 to Event.Swipe(Swipe.UP)), run(frames))
    }

    @Test
    fun theOtherWayCountsAfterAWhile() {
        val frames = many(Sign.POINT_UP, 6) + many(null, 20) + many(Sign.POINT_DOWN, 6)
        assertEquals(listOf(4 to Event.Swipe(Swipe.UP), 30 to Event.Swipe(Swipe.DOWN)), run(frames))
    }

    @Test
    fun aQuickFlickIsNothing() {
        // Pasar por "apuntando" un par de cuadros (moviendo la mano) no cuenta.
        assertEquals(emptyList<Pair<Int, Event>>(), run(many(Sign.POINT_UP, 3) + many(null, 3) + many(Sign.POINT_DOWN, 2)))
    }

    @Test
    fun fistNeedsAlmostASecondAndOnlyOnceUntilTheHandGoesDown() {
        val fist = many(Sign.FIST, 12)
        assertEquals(
            listOf(5 to Event.Sign(Sign.FIST), 19 to Event.Sign(Sign.FIST)),
            run(fist + many(null, 2) + fist),
        )
    }

    @Test
    fun thumbAndVictoryNeedToBeSure() {
        assertEquals(listOf(3 to Event.Sign(Sign.THUMB_UP)), run(many(Sign.THUMB_UP, 6)))
        assertEquals(emptyList<Pair<Int, Event>>(), run(many(Sign.THUMB_UP, 6), sure = false))
        // El puño no lo pide (MediaPipe a veces no lo nombra).
        assertEquals(listOf(5 to Event.Sign(Sign.FIST)), run(many(Sign.FIST, 8), sure = false))
    }

    @Test
    fun theOpenPalmDoesNothing() {
        // La palma se confundía con la mano que usa el teléfono: ya no hace nada sola.
        assertEquals(emptyList<Pair<Int, Event>>(), run(many(Sign.OPEN_PALM, 40)))
    }

    @Test
    fun aHandThatIsNotPresentedDoesNothing() {
        assertEquals(emptyList<Pair<Int, Event>>(), run(many(Sign.POINT_UP, 20), presented = false))
        assertEquals(emptyList<Pair<Int, Event>>(), run(many(Sign.FIST, 20), presented = false))
    }
}
