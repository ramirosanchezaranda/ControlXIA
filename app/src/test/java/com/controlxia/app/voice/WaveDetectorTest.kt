package com.controlxia.app.voice

import com.controlxia.app.voice.WaveDetector.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Saludos y "tapar" con el sensor de proximidad. */
class WaveDetectorTest {

    /** Cambios del sensor: (ms, cerca). Devuelve los eventos con su momento. */
    private fun run(detector: WaveDetector, changes: List<Pair<Long, Boolean>>): List<Pair<Long, Event>> =
        changes.mapNotNull { (t, near) -> detector.feed(near, t)?.let { t to it } }

    private fun far(t: Long) = t to false
    private fun near(t: Long) = t to true

    @Test
    fun oneWave() {
        val d = WaveDetector()
        val events = run(d, listOf(far(0), near(2_000), far(2_250)))
        assertEquals(listOf(2_250L to Event.Wave(1)), events)
    }

    @Test
    fun waveRightAfterStartingCounts() {
        val d = WaveDetector()
        assertEquals(listOf(400L to Event.Wave(1)), run(d, listOf(far(0), near(200), far(400))))
    }

    @Test
    fun twoWavesInARow() {
        val d = WaveDetector()
        val events = run(d, listOf(far(0), near(2_000), far(2_200), near(2_500), far(2_700)))
        assertEquals(listOf(2_200L to Event.Wave(1), 2_700L to Event.Wave(2)), events)
    }

    @Test
    fun slowSecondWaveStartsOver() {
        val d = WaveDetector()
        val events = run(d, listOf(far(0), near(2_000), far(2_200), near(5_000), far(5_200)))
        assertEquals(listOf(2_200L to Event.Wave(1), 5_200L to Event.Wave(1)), events)
    }

    @Test
    fun takingOutOfPocketIsNotAWave() {
        val d = WaveDetector()
        // En el bolsillo (tapado mucho rato) y al sacarlo se destapa.
        assertEquals(emptyList<Pair<Long, Event>>(), run(d, listOf(near(0), far(60_000))))
    }

    @Test
    fun pocketFlickerIsNotAWave() {
        val d = WaveDetector()
        // En el bolsillo, la tela se mueve: se destapa un momento y se vuelve a tapar.
        val events = run(d, listOf(near(0), far(10_000), near(10_200), far(10_400), near(10_500)))
        assertEquals(emptyList<Pair<Long, Event>>(), events)
    }

    @Test
    fun slowPassIsNotAWave() {
        val d = WaveDetector()
        assertEquals(emptyList<Pair<Long, Event>>(), run(d, listOf(far(0), near(2_000), far(3_500))))
    }

    @Test
    fun glitchIsNotAWave() {
        val d = WaveDetector()
        assertEquals(emptyList<Pair<Long, Event>>(), run(d, listOf(far(0), near(2_000), far(2_010))))
    }

    @Test
    fun coverAfterASecond() {
        val d = WaveDetector()
        run(d, listOf(far(0), near(2_000)))
        assertNull("todavía no", d.checkCover(2_500))
        assertEquals(Event.Cover, d.checkCover(3_000))
        assertNull("una sola vez", d.checkCover(3_100))
        // Al destaparse no cuenta como saludo.
        assertNull(d.feed(false, 3_400))
    }

    @Test
    fun coverNeedsToStartUncovered() {
        val d = WaveDetector()
        // Boca abajo desde el principio: no es taparlo a propósito.
        d.feed(true, 0)
        assertNull(d.checkCover(1_000))
    }

    @Test
    fun coverIgnoredIfUncoveredMeanwhileOrTooLate() {
        val d = WaveDetector()
        run(d, listOf(far(0), near(2_000), far(2_300)))
        assertNull("ya se destapó", d.checkCover(3_000))
        val late = WaveDetector()
        run(late, listOf(far(0), near(2_000)))
        assertNull("aviso muy tarde", late.checkCover(6_000))
    }
}
