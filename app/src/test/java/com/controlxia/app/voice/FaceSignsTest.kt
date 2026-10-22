package com.controlxia.app.voice

import com.controlxia.app.voice.FaceSigns.Answer
import com.controlxia.app.voice.FaceSigns.Face
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sin

/** Sí o no con la cara: guiño, asentir, negar. */
class FaceSignsTest {

    /** Cuadros cada [stepMs]; devuelve la primera respuesta y en qué cuadro. */
    private fun run(signs: FaceSigns, frames: List<Face?>, stepMs: Long = 100): Pair<Int, Answer>? {
        frames.forEachIndexed { i, f -> signs.feed(f, i * stepMs)?.let { return i to it } }
        return null
    }

    private val still = Face(0.05f, 0.05f, 0.5f, 0.5f)

    @Test
    fun winkIsYes() {
        val wink = Face(0.85f, 0.08f, 0.5f, 0.5f)
        assertEquals(3 to Answer.YES, run(FaceSigns(), listOf(still, still, wink, wink)))
    }

    @Test
    fun blinkIsNotAWink() {
        val blink = Face(0.9f, 0.85f, 0.5f, 0.5f)
        assertNull(run(FaceSigns(), List(10) { if (it % 3 == 0) blink else still }))
    }

    @Test
    fun oneFrameWinkIsNotEnough() {
        val wink = Face(0.85f, 0.08f, 0.5f, 0.5f)
        assertNull(run(FaceSigns(), listOf(still, wink, still, still)))
    }

    @Test
    fun shakeIsNo() {
        // Izquierda-derecha dos veces en ~1 s (10 cuadros por segundo).
        val frames = List(12) { i -> Face(0.05f, 0.05f, 0.5f + 0.06f * sin(i * 1.3f), 0.5f + 0.005f * sin(i * 0.7f)) }
        assertEquals(Answer.NO, run(FaceSigns(), frames)?.second)
    }

    @Test
    fun nodIsYes() {
        val frames = List(12) { i -> Face(0.05f, 0.05f, 0.5f + 0.005f * sin(i * 0.7f), 0.5f + 0.05f * sin(i * 1.3f)) }
        assertEquals(Answer.YES, run(FaceSigns(), frames)?.second)
    }

    @Test
    fun lookingAsideOnceIsNothing() {
        // Mira para un costado y vuelve: dos movimientos, no tres.
        val xs = listOf(0.5f, 0.5f, 0.56f, 0.6f, 0.6f, 0.6f, 0.55f, 0.5f, 0.5f, 0.5f)
        assertNull(run(FaceSigns(), xs.map { Face(0.05f, 0.05f, it, 0.5f) }))
    }

    @Test
    fun slowDriftIsNothing() {
        val frames = List(30) { i -> Face(0.05f, 0.05f, 0.4f + i * 0.004f, 0.5f) }
        assertNull(run(FaceSigns(), frames))
    }

    @Test
    fun noFaceResets() {
        val wink = Face(0.85f, 0.08f, 0.5f, 0.5f)
        assertNull(run(FaceSigns(), listOf(wink, null, wink, null)))
    }
}
