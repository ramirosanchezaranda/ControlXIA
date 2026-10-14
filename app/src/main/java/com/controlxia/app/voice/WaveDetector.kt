package com.controlxia.app.voice

/**
 * Saludos al teléfono con el sensor de proximidad (el que apaga la pantalla en
 * las llamadas, arriba de la pantalla): solo dice "cerca" o "lejos", pero
 * gasta casi nada y anda con la pantalla apagada.
 *
 * - **Saludo**: pasar la mano por encima, a pocos centímetros. El sensor queda
 *   tapado un instante (menos de [maxNearMs]) y vuelve a destaparse. Saludos
 *   seguidos (con menos de [sequenceGapMs] entre uno y otro) se cuentan.
 * - **Tapar**: dejar la mano encima [coverMs]. Lo avisa [checkCover], que hay
 *   que llamar a ese tiempo de haberse tapado (el sensor no avisa mientras
 *   sigue tapado).
 *
 * Para no confundirse con el bolsillo o con el teléfono boca abajo, solo
 * cuenta si antes de taparse estuvo destapado al menos [minFarBeforeMs] (o si
 * viene de otro saludo de la misma serie). Sin Android: se prueba en la PC.
 */
class WaveDetector(
    private val maxNearMs: Long = 800,
    private val minNearMs: Long = 30,
    private val minFarBeforeMs: Long = 600,
    private val sequenceGapMs: Long = 1_500,
    val coverMs: Long = 1_000,
) {
    sealed interface Event {
        /** Un saludo; [count] es cuántos seguidos van (1, 2…). */
        data class Wave(val count: Int) : Event

        /** La mano quedó encima [coverMs]. */
        data object Cover : Event
    }

    private var near: Boolean? = null
    private var changedAt = 0L
    private var armed = false
    private var coverFired = false
    private var waves = 0
    private var lastWaveAt = Long.MIN_VALUE / 2

    /** Si ahora está tapado. */
    val isNear: Boolean get() = near == true

    /** Un dato nuevo del sensor en el momento [now] (ms). Devuelve un saludo si se completó. */
    fun feed(near: Boolean, now: Long): Event? {
        val before = this.near
        if (before == near) return null
        this.near = near
        val held = now - changedAt
        changedAt = now
        // El primer dato solo dice cómo está. Si está destapado, se toma como
        // que lo estaba desde antes (si no, el primer saludo no contaría).
        if (before == null) {
            if (!near) changedAt = now - minFarBeforeMs
            return null
        }
        if (near) {
            val inSeries = waves > 0 && now - lastWaveAt <= sequenceGapMs
            armed = held >= minFarBeforeMs || inSeries
            coverFired = false
            return null
        }
        // Se destapó.
        val wasArmed = armed
        armed = false
        if (!wasArmed || coverFired || held !in minNearMs..maxNearMs) {
            waves = 0
            return null
        }
        if (now - lastWaveAt > sequenceGapMs) waves = 0
        waves++
        lastWaveAt = now
        return Event.Wave(waves)
    }

    /**
     * Llamar [coverMs] después de taparse: [Event.Cover] si sigue tapado desde
     * entonces (y empezó destapado). Una sola vez por cada vez que se tapa.
     */
    fun checkCover(now: Long): Event? {
        if (near != true || !armed || coverFired) return null
        val held = now - changedAt
        if (held < coverMs || held > coverMs + LATE_MS) return null
        coverFired = true
        waves = 0
        return Event.Cover
    }

    fun reset() {
        near = null
        armed = false
        coverFired = false
        waves = 0
    }

    private companion object {
        /** Si el aviso llega muy tarde (el teléfono estaba dormido), ya no cuenta. */
        const val LATE_MS = 1_500L
    }
}
