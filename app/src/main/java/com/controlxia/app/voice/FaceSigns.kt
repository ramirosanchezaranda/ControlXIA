package com.controlxia.app.voice

/**
 * Contestar sí o no con la cara cuando el asistente pregunta algo ("¿Le
 * mando…?"): guiñar un ojo o asentir es "sí"; negar con la cabeza es "no".
 *
 * Cada cuadro trae cuánto está cerrado cada ojo (0 a 1, de MediaPipe) y dónde
 * está la punta de la nariz (0 a 1 en la imagen). Un guiño es un ojo cerrado y
 * el otro abierto unos cuadros seguidos (un parpadeo cierra los dos y no
 * cuenta). Asentir o negar es mover la cabeza ida y vuelta al menos tres veces
 * en poco tiempo, más en un eje que en el otro. Sin Android: se prueba en la PC.
 */
class FaceSigns(
    private val winkFrames: Int = 2,
    private val windowMs: Long = 1_600,
    private val minSwing: Float = 0.03f,
    private val swingsNeeded: Int = 3,
    private val cooldownMs: Long = 2_000,
) {
    enum class Answer { YES, NO }

    /** Un cuadro con una cara. */
    data class Face(val blinkLeft: Float, val blinkRight: Float, val noseX: Float, val noseY: Float)

    private var winkStreak = 0
    private val track = ArrayDeque<Triple<Long, Float, Float>>()
    private var lastAnswerAt = Long.MIN_VALUE / 2

    /** Un cuadro más ([face] null: no se ve ninguna cara). Devuelve la respuesta si se completó. */
    fun feed(face: Face?, now: Long): Answer? {
        if (face == null) {
            winkStreak = 0
            track.clear()
            return null
        }
        if (now - lastAnswerAt < cooldownMs) return null
        // Guiño: un ojo bien cerrado y el otro abierto.
        val closed = maxOf(face.blinkLeft, face.blinkRight)
        val open = minOf(face.blinkLeft, face.blinkRight)
        winkStreak = if (closed >= CLOSED && open <= OPEN) winkStreak + 1 else 0
        if (winkStreak >= winkFrames) return answer(Answer.YES, now)

        track.addLast(Triple(now, face.noseX, face.noseY))
        while (track.isNotEmpty() && now - track.first().first > windowMs) track.removeFirst()
        if (track.size < swingsNeeded + 1) return null
        val xs = track.map { it.second }
        val ys = track.map { it.third }
        val xRange = xs.max() - xs.min()
        val yRange = ys.max() - ys.min()
        if (swings(xs) >= swingsNeeded && xRange > yRange * DOMINANT) return answer(Answer.NO, now)
        if (swings(ys) >= swingsNeeded && yRange > xRange * DOMINANT) return answer(Answer.YES, now)
        return null
    }

    fun reset() {
        winkStreak = 0
        track.clear()
    }

    private fun answer(answer: Answer, now: Long): Answer {
        lastAnswerAt = now
        reset()
        return answer
    }

    /** Cuántas veces se movió al menos [minSwing] en un sentido y después en el otro. */
    internal fun swings(values: List<Float>): Int {
        var count = 0
        var extreme = values.first()
        var direction = 0
        for (v in values.drop(1)) {
            val delta = v - extreme
            when {
                direction == 0 && kotlin.math.abs(delta) >= minSwing -> {
                    direction = if (delta > 0) 1 else -1
                    count = 1
                    extreme = v
                }
                direction != 0 && delta * direction > 0 -> extreme = v
                direction != 0 && kotlin.math.abs(delta) >= minSwing -> {
                    direction = -direction
                    count++
                    extreme = v
                }
            }
        }
        return count
    }

    private companion object {
        const val CLOSED = 0.5f
        const val OPEN = 0.25f
        const val DOMINANT = 1.5f
    }
}
