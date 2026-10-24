package com.controlxia.app.voice

import com.controlxia.app.voice.HandSigns.Point
import com.controlxia.app.voice.HandSigns.Sign
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Las señas con la mano (con manos sintéticas) y cuándo disparan una acción. */
class HandSignsTest {

    private val pointing = listOf(true, false, false, false)

    @Test
    fun pointingDirections() {
        assertEquals(Sign.POINT_UP, HandSigns.classify("None", 0.9f, hand(pointing)))
        assertEquals(Sign.POINT_UP, HandSigns.classify("Pointing_Up", 0.9f, emptyList()))
        assertEquals(Sign.POINT_DOWN, HandSigns.classify("None", 0.9f, hand(pointing, 180.0)))
        // Girada 90° en la imagen: apunta a la derecha de la imagen = tu izquierda (cámara frontal sin espejar).
        assertEquals(Sign.POINT_LEFT, HandSigns.classify("None", 0.9f, hand(pointing, 90.0)))
        assertEquals(Sign.POINT_RIGHT, HandSigns.classify("None", 0.9f, hand(pointing, -90.0)))
        assertEquals(Sign.POINT_RIGHT, HandSigns.classify("None", 0.9f, hand(pointing, 90.0), mirrored = true))
        assertEquals("en diagonal no cuenta", Sign.NONE, HandSigns.classify("None", 0.9f, hand(pointing, 45.0)))
        assertEquals("índice y medio no es apuntar", Sign.NONE, HandSigns.classify("None", 0.9f, hand(listOf(true, true, false, false))))
    }

    @Test
    fun mediaPipeGestures() {
        val fist = hand(listOf(false, false, false, false))
        assertEquals(Sign.OPEN_PALM, HandSigns.classify("Open_Palm", 0.9f, hand(listOf(true, true, true, true))))
        assertEquals(Sign.VICTORY, HandSigns.classify("Victory", 0.9f, hand(listOf(true, true, false, false))))
        assertEquals(Sign.THUMB_UP, HandSigns.classify("Thumb_Up", 0.8f, fist))
        assertEquals(Sign.FIST, HandSigns.classify("Closed_Fist", 0.8f, fist))
        assertEquals("poca confianza", Sign.NONE, HandSigns.classify("Thumb_Up", 0.3f, fist))
    }

    @Test
    fun triggerNeedsThreeFramesAndRespectsPauses() {
        val up = Sign.POINT_UP
        val none = Sign.NONE
        val like = Sign.THUMB_UP
        assertEquals(listOf(2 to up), run(listOf(up, up, up, up)))
        assertEquals(emptyList<Pair<Int, Sign>>(), run(listOf(up, up, none, none)))
        assertEquals("mantener repite cada 1,8 s", listOf(2 to up, 11 to up), run(List(16) { up }))
        assertEquals("un parpadeo no repite", listOf(2 to up), run(listOf(up, up, up, none, up, up, up)))
        assertEquals(listOf(2 to up, 7 to up), run(listOf(up, up, up, none, none, up, up, up)))
        assertEquals("el like no se repite", listOf(2 to like), run(List(20) { like }))
        assertEquals(listOf(2 to up, 7 to like), run(listOf(up, up, up, like, like, like, like, like)))
    }

    @Test
    fun palmNeedsALongerHold() {
        // La palma también se usa para deslizar: para pausar hay que sostenerla más.
        val trigger = HandSigns.Trigger(framesNeeded = 4, framesFor = mapOf(Sign.OPEN_PALM to 10))
        val fired = List(12) { Sign.OPEN_PALM }.mapIndexedNotNull { i, s -> trigger.feed(s, i * 100L)?.let { i to it } }
        assertEquals(listOf(9 to Sign.OPEN_PALM), fired)
        val up = HandSigns.Trigger(framesNeeded = 4, framesFor = mapOf(Sign.OPEN_PALM to 10))
        val point = List(5) { Sign.POINT_UP }.mapIndexedNotNull { i, s -> up.feed(s, i * 100L)?.let { i to it } }
        assertEquals(listOf(3 to Sign.POINT_UP), point)
    }

    @Test
    fun openHandWithoutMediaPipeNameIsPalm() {
        // Los dedos juntos, como para deslizar: MediaPipe no siempre la llama "Open_Palm".
        assertEquals(Sign.OPEN_PALM, HandSigns.classify(null, 0f, hand(listOf(true, true, true, true), angleDeg = 25.0)))
        assertEquals(Sign.FIST, HandSigns.classify(null, 0f, hand(listOf(false, false, false, false))))
        assertEquals(Sign.THUMB_UP, HandSigns.classify("Thumb_Up", 0.9f, hand(listOf(false, false, false, false))))
    }

    @Test
    fun fistWithoutMediaPipeName() {
        // Los cuatro dedos doblados y el pulgar al costado (pegado): puño.
        assertEquals(Sign.FIST, HandSigns.classify(null, 0f, hand(listOf(false, false, false, false))))
        // Con un dedo estirado ya no es puño.
        assertEquals(Sign.NONE, HandSigns.classify(null, 0f, hand(listOf(false, true, false, false))))
    }

    /** Cuadros cada 200 ms; devuelve en qué cuadro disparó cada seña. */
    private fun run(signs: List<Sign>): List<Pair<Int, Sign>> {
        val trigger = HandSigns.Trigger()
        return signs.mapIndexedNotNull { i, s -> trigger.feed(s, i * 200L)?.let { i to it } }
    }

    /** Una mano sintética con la palma hacia la cámara; [extended]: índice, medio, anular, meñique. */
    private fun hand(extended: List<Boolean>, angleDeg: Double = 0.0): List<Point> {
        val pts = MutableList(21) { Point(0.5f, 0.6f) }
        pts[0] = Point(0.5f, 0.8f)
        val mcpX = listOf(0.44f, 0.5f, 0.55f, 0.6f)
        listOf(5, 9, 13, 17).forEachIndexed { f, mcp ->
            val base = Point(mcpX[f], 0.6f)
            val tip = if (extended[f]) Point(base.x, base.y - 0.19f) else Point(base.x, base.y + 0.08f)
            pts[mcp] = base
            pts[mcp + 1] = Point((base.x + tip.x) / 2, (base.y + tip.y) / 2)
            pts[mcp + 2] = pts[mcp + 1]
            pts[mcp + 3] = tip
        }
        for (i in 1..4) pts[i] = Point(0.4f - i * 0.01f, 0.7f) // pulgar al costado
        val a = Math.toRadians(angleDeg)
        val c = Point(0.5f, 0.65f)
        return pts.map { p ->
            val x = p.x - c.x
            val y = p.y - c.y
            Point((c.x + x * cos(a) - y * sin(a)).toFloat(), (c.y + x * sin(a) + y * cos(a)).toFloat())
        }
    }
}
