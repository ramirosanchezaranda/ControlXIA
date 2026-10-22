package com.controlxia.app.voice

import com.controlxia.app.voice.HandMotion.Swipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Deslizar en el aire: mano quieta y movimiento rápido. */
class HandMotionTest {

    /** Cuadros cada 100 ms: (x, y) o null sin mano. Devuelve los deslizamientos con su cuadro. */
    private fun run(motion: HandMotion, frames: List<Pair<Float, Float>?>, mirrored: Boolean = false): List<Pair<Int, Swipe>> =
        frames.mapIndexedNotNull { i, p -> motion.feed(p?.first, p?.second, i * 100L, mirrored)?.let { i to it } }

    private fun still(x: Float, y: Float, n: Int) = List(n) { x to y }

    @Test
    fun stillThenUpIsUp() {
        val frames = still(0.5f, 0.6f, 5) + listOf(0.5f to 0.5f, 0.5f to 0.35f)
        assertEquals(listOf(6 to Swipe.UP), run(HandMotion(), frames))
    }

    @Test
    fun stillThenDownIsDown() {
        val frames = still(0.5f, 0.4f, 5) + listOf(0.5f to 0.5f, 0.51f to 0.65f)
        assertEquals(listOf(6 to Swipe.DOWN), run(HandMotion(), frames))
    }

    @Test
    fun raisingTheHandIntoViewIsNothing() {
        // La mano entra desde abajo moviéndose y se queda: no hay deslizamiento.
        val frames = listOf(0.5f to 0.95f, 0.5f to 0.8f, 0.5f to 0.65f, 0.5f to 0.55f) + still(0.5f, 0.55f, 4)
        assertEquals(emptyList<Pair<Int, Swipe>>(), run(HandMotion(), frames))
    }

    @Test
    fun swipeThatLeavesTheImageCounts() {
        // Sube rápido y se sale por arriba antes de llegar a la distancia completa.
        val frames = still(0.5f, 0.5f, 5) + listOf(0.5f to 0.38f, null)
        assertEquals(listOf(6 to Swipe.UP), run(HandMotion(), frames))
    }

    @Test
    fun sidewaysDependsOnMirroring() {
        // Hacia la derecha de la imagen: sin espejar es tu izquierda.
        val frames = still(0.4f, 0.5f, 5) + listOf(0.5f to 0.5f, 0.65f to 0.51f)
        assertEquals(listOf(6 to Swipe.LEFT), run(HandMotion(), frames))
        assertEquals(listOf(6 to Swipe.RIGHT), run(HandMotion(), frames, mirrored = true))
    }

    @Test
    fun diagonalIsNothing() {
        val frames = still(0.4f, 0.6f, 5) + listOf(0.5f to 0.5f, 0.6f to 0.4f)
        assertEquals(emptyList<Pair<Int, Swipe>>(), run(HandMotion(), frames))
    }

    @Test
    fun needsToBeStillAgainForASecondSwipe() {
        val frames = still(0.5f, 0.6f, 5) + listOf(0.5f to 0.4f, 0.5f to 0.6f, 0.5f to 0.4f) +
            still(0.5f, 0.6f, 5) + listOf(0.5f to 0.4f)
        val swipes = run(HandMotion(cooldownMs = 300), frames)
        assertEquals(listOf(Swipe.UP, Swipe.UP), swipes.map { it.second })
        assertEquals(13, swipes.last().first)
    }

    @Test
    fun noHandIsNothing() {
        assertNull(HandMotion().feed(null, null, 0))
    }
}
