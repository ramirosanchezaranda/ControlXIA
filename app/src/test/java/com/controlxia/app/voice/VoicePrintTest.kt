package com.controlxia.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** La huella de voz: parecido, promedio y umbral a partir de la inscripción. */
class VoicePrintTest {

    private val random = Random(3)

    /** Una "voz": un vector base; cada grabación le suma un poco de ruido. */
    private fun voice(): FloatArray = FloatArray(128) { random.nextFloat() * 2 - 1 }

    private fun take(base: FloatArray, noise: Float) = FloatArray(base.size) { base[it] + (random.nextFloat() * 2 - 1) * noise }

    @Test
    fun sameVoiceIsCloserThanAnother() {
        val me = voice()
        val other = voice()
        val enrolled = List(3) { take(me, 0.4f) }
        val print = VoicePrint.average(enrolled)
        val threshold = VoicePrint.threshold(enrolled)
        val mine = VoicePrint.similarity(take(me, 0.4f), print)
        val theirs = VoicePrint.similarity(take(other, 0.4f), print)
        assertTrue("yo $mine ≥ $threshold", mine >= threshold)
        assertTrue("otro $theirs < $threshold", theirs < threshold)
    }

    @Test
    fun thresholdStaysInASafeRange() {
        val me = voice()
        val tight = List(3) { take(me, 0.01f) }
        val loose = List(3) { take(me, 3f) }
        assertEquals(0.80f, VoicePrint.threshold(tight), 0.001f)
        assertEquals(0.45f, VoicePrint.threshold(loose), 0.001f)
    }

    @Test
    fun encodeAndDecode() {
        val print = voice()
        val back = VoicePrint.decode(VoicePrint.encode(print))
        assertNotNull(back)
        assertEquals(1f, VoicePrint.similarity(print, back!!), 0.0001f)
    }

    @Test
    fun readsVoskResults() {
        val sample = VoiceId.parse("""{"spk": [0.5, -1.25, 2.0], "spk_frames": 87, "text": "girasol azul"}""")!!
        assertEquals("girasol azul", sample.text)
        assertEquals(87, sample.frames)
        assertEquals(3, sample.print!!.size)
        val noVoice = VoiceId.parse("""{"text": "girasol"}""")!!
        assertEquals(null, noVoice.print)
    }
}
