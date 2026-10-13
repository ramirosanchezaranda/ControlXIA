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
