package com.controlxia.app.voice

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Señas de una mano a partir de lo que ve la cámara: los 21 puntos de la mano
 * de MediaPipe y su gesto. MediaPipe reconoce "dedo para arriba" pero no para
 * abajo ni a los costados, así que la dirección del índice se calcula con los
 * puntos. Sin Android, para poder probarlo en la PC.
 */
object HandSigns {

    enum class Sign { NONE, POINT_UP, POINT_DOWN, POINT_LEFT, POINT_RIGHT, THUMB_UP, THUMB_DOWN, VICTORY, OPEN_PALM, FIST }

    /** Un punto de la mano, de 0 a 1 en la imagen (y crece hacia abajo). */
    data class Point(val x: Float, val y: Float)

    /**
     * La seña de una mano. [category] y [score] son el gesto de MediaPipe
     * ("Pointing_Up", "Thumb_Up"…), [points] los 21 puntos y [aspect] el ancho
     * sobre el alto de la imagen (para medir ángulos sin deformar). [mirrored]
     * dice si la imagen está espejada como una selfie; la de la cámara frontal
     * sin procesar no lo está, y ahí tu derecha aparece a la izquierda.
     */
    fun classify(
        category: String?,
        score: Float,
        points: List<Point>,
        aspect: Float = 1f,
        mirrored: Boolean = false,
    ): Sign {
        pointing(points, aspect, mirrored)?.let { return it }
        // Si MediaPipe no la nombró (o dudó entre la misma), vale la forma de la mano: con los dedos
        // juntos no siempre la llama "Open_Palm", y el puño de costado no siempre "Closed_Fist".
        // Si cree que es otra seña, aunque dude, no se inventa una.
        val unnamed = category == null || category == "None"
        if ((unnamed || (category == "Open_Palm" && score < MIN_SCORE)) && isOpenHand(points, aspect)) return Sign.OPEN_PALM
        if ((unnamed || (category == "Closed_Fist" && score < MIN_SCORE)) && isFist(points, aspect)) return Sign.FIST
        if (score < MIN_SCORE) return Sign.NONE
        return when (category) {
            "Pointing_Up" -> Sign.POINT_UP
            "Thumb_Up" -> Sign.THUMB_UP
            "Thumb_Down" -> Sign.THUMB_DOWN
            "Victory" -> Sign.VICTORY
            "Open_Palm" -> Sign.OPEN_PALM
            "Closed_Fist" -> Sign.FIST
            else -> Sign.NONE
        }
    }

    /**
     * Si la mano se presenta a la cámara: entera en la imagen y a una distancia
     * razonable. Una mano cortada en el borde, pegada a la pantalla (la que usa
     * el teléfono) o lejos (alguien atrás) no hace señas.
     */
    fun isPresented(points: List<Point>, aspect: Float = 1f): Boolean {
        if (points.size < 21) return false
        if (points.any { it.x < EDGE || it.x > 1 - EDGE || it.y < EDGE || it.y > 1 - EDGE }) return false
        return palmSize(points, aspect) in MIN_HAND..MAX_HAND
    }

    /** Del puño al nudillo del medio, en altos de imagen (0.17 ≈ a medio metro). */
    fun palmSize(points: List<Point>, aspect: Float = 1f): Float =
        hypot((points[WRIST].x - points[MIDDLE_MCP].x) * aspect, points[WRIST].y - points[MIDDLE_MCP].y)

    /** Si MediaPipe reconoció esa misma seña y con confianza (no solo por la forma de la mano). */
    fun isSure(category: String?, score: Float, sign: Sign): Boolean = score >= SURE_SCORE && when (category) {
        "Thumb_Up" -> sign == Sign.THUMB_UP
        "Thumb_Down" -> sign == Sign.THUMB_DOWN
        "Victory" -> sign == Sign.VICTORY
        "Open_Palm" -> sign == Sign.OPEN_PALM
        "Closed_Fist" -> sign == Sign.FIST
        else -> false
    }

    /** La mano abierta: los cuatro dedos estirados, juntos o separados. */
    fun isOpenHand(points: List<Point>, aspect: Float = 1f): Boolean {
        if (points.size < 21) return false
        fun p(i: Int) = Point(points[i].x * aspect, points[i].y)
        fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
        val palm = dist(p(WRIST), p(MIDDLE_MCP))
        if (palm < 1e-3f) return false
        fun reach(tip: Int, mcp: Int) = dist(p(tip), p(mcp)) / palm
        // El meñique es más corto: se le pide menos.
        return reach(INDEX_TIP, INDEX_MCP) >= OPEN && reach(MIDDLE_TIP, MIDDLE_MCP) >= OPEN &&
            reach(RING_TIP, RING_MCP) >= OPEN && reach(PINKY_TIP, PINKY_MCP) >= OPEN_PINKY
    }

    /** El puño: los cuatro dedos doblados y el pulgar pegado (si el pulgar se estira, es 👍 o 👎). */
    fun isFist(points: List<Point>, aspect: Float = 1f): Boolean {
        if (points.size < 21) return false
        fun p(i: Int) = Point(points[i].x * aspect, points[i].y)
        fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
        val palm = dist(p(WRIST), p(MIDDLE_MCP))
        if (palm < 1e-3f) return false
        fun reach(tip: Int, mcp: Int) = dist(p(tip), p(mcp)) / palm
        val folded = listOf(reach(INDEX_TIP, INDEX_MCP), reach(MIDDLE_TIP, MIDDLE_MCP), reach(RING_TIP, RING_MCP), reach(PINKY_TIP, PINKY_MCP))
            .all { it < FIST_FOLDED }
        val thumbOut = dist(p(THUMB_TIP), p(INDEX_MCP)) / palm > THUMB_OUT
        return folded && !thumbOut
    }

    /** Índice estirado y los otros tres doblados: ¿hacia dónde apunta? null si no apunta. */
    private fun pointing(points: List<Point>, aspect: Float, mirrored: Boolean): Sign? {
        if (points.size < 21) return null
        fun p(i: Int) = Point(points[i].x * aspect, points[i].y)
        fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
        val palm = dist(p(WRIST), p(MIDDLE_MCP))
        if (palm < 1e-3f) return null
        fun reach(tip: Int, mcp: Int) = dist(p(tip), p(mcp)) / palm
        val index = reach(INDEX_TIP, INDEX_MCP)
        val folded = listOf(reach(MIDDLE_TIP, MIDDLE_MCP), reach(RING_TIP, RING_MCP), reach(PINKY_TIP, PINKY_MCP))
        if (index < EXTENDED || folded.any { it > FOLDED }) return null
        val dx = p(INDEX_TIP).x - p(INDEX_MCP).x
        val dy = p(INDEX_TIP).y - p(INDEX_MCP).y
        return when {
            abs(dy) >= abs(dx) * DOMINANT -> if (dy < 0) Sign.POINT_UP else Sign.POINT_DOWN
            abs(dx) >= abs(dy) * DOMINANT -> {
                val towardImageRight = dx > 0
                // Espejada, la derecha de la imagen es tu derecha; sin espejar, es tu izquierda.
                if (towardImageRight == mirrored) Sign.POINT_RIGHT else Sign.POINT_LEFT
            }
            else -> null // En diagonal: mejor no hacer nada.
        }
    }

    /**
     * Decide cuándo una seña cuenta: tiene que verse [framesNeeded] cuadros
     * seguidos, hay una pausa mínima entre acciones y, para repetir la misma,
     * hay que bajar la mano o (solo las que se pueden repetir, como pasar de
     * reel) mantenerla [repeatMs].
     */
    class Trigger(
        private val framesNeeded: Int = 3,
        private val repeatMs: Long = 1_800,
        private val cooldownMs: Long = 900,
        private val repeatable: Set<Sign> = setOf(Sign.POINT_UP, Sign.POINT_DOWN, Sign.POINT_LEFT, Sign.POINT_RIGHT),
        /** Señas que tienen que sostenerse más (la palma, que también se usa para deslizar). */
        private val framesFor: Map<Sign, Int> = emptyMap(),
    ) {
        private var current = Sign.NONE
        private var streak = 0
        private var noneStreak = 0
        private var fired = Sign.NONE
        private var firedAt = 0L
        private var lastFireAt = Long.MIN_VALUE / 2

        /** Un cuadro más. Devuelve la seña si hay que actuar ahora. */
        fun feed(sign: Sign, now: Long): Sign? {
            if (sign == Sign.NONE) {
                noneStreak++
                // Un cuadro sin mano puede ser un parpadeo de la detección; dos, bajaste la mano.
                if (noneStreak >= 2) {
                    fired = Sign.NONE
                    current = Sign.NONE
                    streak = 0
                }
                return null
            }
            noneStreak = 0
            if (sign != current) {
                current = sign
                streak = 0
            }
            streak++
            if (streak < (framesFor[sign] ?: framesNeeded)) return null
            if (now - lastFireAt < cooldownMs) return null
            if (sign == fired && (sign !in repeatable || now - firedAt < repeatMs)) return null
            fired = sign
            firedAt = now
            lastFireAt = now
            return sign
        }

        fun reset() {
            current = Sign.NONE
            streak = 0
            noneStreak = 0
            fired = Sign.NONE
        }
    }

    private const val WRIST = 0
    private const val THUMB_TIP = 4
    private const val INDEX_MCP = 5
    private const val INDEX_TIP = 8
    private const val MIDDLE_MCP = 9
    private const val MIDDLE_TIP = 12
    private const val RING_MCP = 13
    private const val RING_TIP = 16
    private const val PINKY_MCP = 17
    private const val PINKY_TIP = 20

    /** Punta-nudillo sobre el largo de la palma: estirado arriba de esto… */
    private const val EXTENDED = 0.72f
    /** …y doblado abajo de esto. */
    private const val FOLDED = 0.62f
    /** Para la mano abierta (los dedos juntos de costado se ven un poco más cortos). */
    private const val OPEN = 0.6f
    private const val OPEN_PINKY = 0.45f
    /** Margen del borde de la imagen y tamaños de mano que cuentan (en altos de imagen). */
    private const val EDGE = 0.02f
    private const val MIN_HAND = 0.07f
    private const val MAX_HAND = 0.45f
    private const val SURE_SCORE = 0.7f
    /** Para el puño: las puntas cerca de los nudillos, y el pulgar sin estirar. */
    private const val FIST_FOLDED = 0.55f
    private const val THUMB_OUT = 0.9f
    /** Cuánto más tiene que ser un eje que el otro para contar como esa dirección. */
    private const val DOMINANT = 1.3f
    private const val MIN_SCORE = 0.5f
}
