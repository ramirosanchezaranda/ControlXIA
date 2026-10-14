package com.controlxia.app.voice

import kotlin.math.sqrt

/**
 * Decide, bloque a bloque, si en el micrófono hay algo que valga la pena
 * reconocer. La escucha de la palabra de activación pasa casi todo el día en
 * silencio: con esta compuerta el reconocedor no trabaja mientras no hay
 * sonido (es lo que más batería gasta), y el micrófono sigue abierto.
 *
 * Mide el volumen de cada bloque contra el ruido de fondo, que va aprendiendo
 * solo (baja enseguida si el lugar se calma y sube despacio si aparece un
 * ruido constante). Se abre con un bloque claramente más fuerte que el fondo y
 * se cierra después de [hangChunks] bloques sin nada. Sin Android: se prueba en
 * la PC con audio real.
 */
class SpeechGate(
    /** Cuántas veces más fuerte que el fondo tiene que ser un bloque (2 ≈ +6 dB). */
    private val ratio: Double = 2.0,
    /** Por debajo de esto (RMS sobre 32768) siempre es silencio. */
    private val minLevel: Double = 60.0,
    /** Bloques en silencio antes de cerrar (con bloques de 100 ms, 1,2 s). */
    private val hangChunks: Int = 12,
) {
    /** Ruido de fondo aprendido (RMS); negativo hasta el primer bloque. */
    var floor: Double = -1.0
        private set

    /** Si la compuerta está abierta (hay que reconocer). */
    var open: Boolean = false
        private set

    private var quiet = 0

    /** Mira un bloque nuevo de [n] muestras. true si hay que reconocerlo. */
    fun update(chunk: ShortArray, n: Int): Boolean {
        val level = rms(chunk, n)
        val threshold = maxOf((if (floor < 0) level else floor) * ratio, minLevel)
        val loud = level > threshold && floor >= 0
        floor = when {
            floor < 0 -> minOf(level, START_FLOOR)
            level < floor -> floor * 0.7 + level * 0.3 // se calmó: aprender rápido
            loud -> floor * 0.999 + level * 0.001 // voz: casi no cuenta
            else -> floor * 0.99 + level * 0.01 // ruido que sube despacio
        }
        if (loud) {
            open = true
            quiet = 0
        } else if (open && ++quiet >= hangChunks) {
            open = false
        }
        return open
    }

    companion object {
        /** El primer bloque puede ser voz: no se toma como fondo más de esto. */
        private const val START_FLOOR = 300.0

        fun rms(chunk: ShortArray, n: Int): Double {
            if (n <= 0) return 0.0
            var sum = 0.0
            for (i in 0 until n) {
                val v = chunk[i].toDouble()
                sum += v * v
            }
            return sqrt(sum / n)
        }
    }
}
