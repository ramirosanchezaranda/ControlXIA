package com.controlxia.app.voice

import kotlin.math.sqrt

/**
 * La huella de una voz: un vector de números (x-vector de Vosk) por cada vez
 * que se dice algo. Dos dichos de la misma persona dan vectores parecidos
 * (coseno alto). Al guardar la voz se promedian varias grabaciones y el
 * umbral se ajusta a cuánto se parecen entre sí: alguien con una voz muy pareja
 * queda con un umbral más alto. Sin Android: se prueba en la PC.
 */
object VoicePrint {

    /** Parecido entre dos huellas, de -1 a 1 (1 = idénticas). */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    /** El promedio de varias huellas (cada una normalizada antes, para que pesen igual). */
    fun average(prints: List<FloatArray>): FloatArray {
        require(prints.isNotEmpty())
        val size = prints.first().size
        val sum = FloatArray(size)
        prints.forEach { p ->
            val norm = sqrt(p.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0f } ?: 1f
            for (i in 0 until size) sum[i] += p[i] / norm
        }
        return FloatArray(size) { sum[it] / prints.size }
    }

    /**
     * El umbral para aceptar: un poco por debajo de lo que se parecen entre sí
     * las grabaciones de la inscripción, dentro de un rango seguro.
     */
    fun threshold(prints: List<FloatArray>): Float {
        val pairs = mutableListOf<Float>()
        for (i in prints.indices) for (j in i + 1 until prints.size) pairs += similarity(prints[i], prints[j])
        val mean = if (pairs.isEmpty()) DEFAULT else pairs.average().toFloat()
        return (mean - MARGIN).coerceIn(MIN, MAX)
    }

    /** Qué tan parejas salieron las grabaciones (0 a 1), para avisar si conviene repetir. */
    fun consistency(prints: List<FloatArray>): Float {
        val avg = average(prints)
        return prints.map { similarity(it, avg) }.minOrNull() ?: 0f
    }

    fun encode(print: FloatArray): String = print.joinToString(",")

    fun decode(text: String): FloatArray? =
        text.split(',').mapNotNull { it.trim().toFloatOrNull() }.takeIf { it.size >= MIN_SIZE }?.toFloatArray()

    private const val DEFAULT = 0.75f
    private const val MARGIN = 0.15f
    private const val MIN = 0.45f
    private const val MAX = 0.80f
    private const val MIN_SIZE = 16
}
