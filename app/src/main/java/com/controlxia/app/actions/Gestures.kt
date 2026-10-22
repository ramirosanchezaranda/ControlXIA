package com.controlxia.app.actions

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Toques y deslizamientos "con el dedo", para lo que no se puede tocar con una acción. */
object Gestures {

    fun tapAt(service: AccessibilityService, x: Float, y: Float, durationMs: Long = 60): Boolean =
        stroke(service, Path().apply { moveTo(x, y) }, durationMs)

    /** Dos toques rápidos en el mismo lugar ("me gusta" en reels y videos cortos). */
    fun doubleTapAt(service: AccessibilityService, x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return service.dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .addStroke(GestureDescription.StrokeDescription(path, 140, 50))
                .build(),
            null,
            null,
        )
    }

    /** Toca el centro del elemento. */
    fun tap(service: AccessibilityService, node: AccessibilityNodeInfo, durationMs: Long = 60): Boolean {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.isEmpty) return false
        return tapAt(service, bounds.exactCenterX(), bounds.exactCenterY(), durationMs)
    }

    /** Mantener apretado: abre menús, selecciona texto, graba audios. */
    fun longPress(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
        return tap(service, node, durationMs = 900)
    }

    /**
     * Un deslizamiento rápido como el del dedo ([up]: el contenido sube, el
     * reel siguiente). Las apps de videos (Instagram, TikTok, Shorts) pasan de
     * video con esto aunque no respondan a "desplazar" de la accesibilidad.
     */
    fun flingVertical(service: AccessibilityService, up: Boolean): Boolean {
        val metrics = service.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val (from, to) = if (up) 0.72f to 0.25f else 0.28f to 0.75f
        return swipe(service, x, metrics.heightPixels * from, x, metrics.heightPixels * to, FLING_MS)
    }

    /** Deslizar de costado como el dedo ([toLeft]: la foto o historia siguiente). */
    fun flingHorizontal(service: AccessibilityService, toLeft: Boolean): Boolean {
        val metrics = service.resources.displayMetrics
        val y = metrics.heightPixels / 2f
        val (from, to) = if (toLeft) 0.85f to 0.15f else 0.15f to 0.85f
        return swipe(service, metrics.widthPixels * from, y, metrics.widthPixels * to, y, FLING_MS)
    }

    private const val FLING_MS = 180L

    /** Desliza de (x1, y1) a (x2, y2). */
    fun swipe(
        service: AccessibilityService,
        x1: Float, y1: Float, x2: Float, y2: Float,
        durationMs: Long = 300,
    ): Boolean = stroke(service, Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, durationMs)

    private fun stroke(service: AccessibilityService, path: Path, durationMs: Long): Boolean =
        service.dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build(),
            null,
            null,
        )
}
