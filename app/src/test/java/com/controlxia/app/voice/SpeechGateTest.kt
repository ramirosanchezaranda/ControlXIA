package com.controlxia.app.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** La compuerta que deja dormir al reconocedor mientras no hay sonido. */
class SpeechGateTest {

    private val random = Random(7)

    /** Un bloque de 100 ms: ruido de fondo de [noise] más un tono de [tone] (RMS aproximado). */
    private fun chunk(noise: Double, tone: Double = 0.0, start: Int = 0): ShortArray = ShortArray(CHUNK) { i ->
        val n = random.nextDouble(-1.0, 1.0) * noise * 1.73
        val t = sin(2 * PI * 220 * (start + i) / 16_000.0) * tone * 1.41
        (n + t).coerceIn(-32768.0, 32767.0).toInt().toShort()
    }

    private fun SpeechGate.feed(block: ShortArray) = update(block, block.size)

    @Test
    fun staysClosedInSilenceAndSteadyNoise() {
        val gate = SpeechGate()
        repeat(100) { assertFalse("silencio", gate.feed(chunk(5.0))) }
        val noisy = SpeechGate()
        repeat(100) { assertFalse("ruido constante", noisy.feed(chunk(400.0))) }
    }

    @Test
    fun opensWithVoiceAndClosesAfterHang() {
        val gate = SpeechGate(hangChunks = 12)
        repeat(30) { gate.feed(chunk(20.0)) }
        assertTrue("se abre con voz", gate.feed(chunk(20.0, tone = 2000.0)))
        repeat(11) { assertTrue("sigue abierta un rato", gate.feed(chunk(20.0))) }
        assertFalse("se cierra después de la pausa", gate.feed(chunk(20.0)))
    }

    @Test
    fun opensOverLoudBackground() {
        val gate = SpeechGate()
        repeat(50) { gate.feed(chunk(400.0)) }
        assertTrue(gate.feed(chunk(400.0, tone = 2500.0)))
    }

    @Test
    fun ignoresQuietSoundsBelowMinimum() {
        val gate = SpeechGate(minLevel = 60.0)
        repeat(30) { gate.feed(chunk(2.0)) }
        assertFalse(gate.feed(chunk(2.0, tone = 30.0)))
    }

    private companion object {
        const val CHUNK = 1_600
    }
}
