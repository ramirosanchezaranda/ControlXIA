package com.controlxia.app.actions

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * Lo básico para manejar otras apps por accesibilidad, en un solo lugar:
 * recorrer la pantalla, tocar, escribir y esperar a que algo aparezca. Lo usan
 * WhatsApp, cualquier app, los números en pantalla y los ajustes.
 */
object Nodes {

    /** Recorre lo visible de la pantalla (hasta [maxDepth] niveles). */
    fun visit(root: AccessibilityNodeInfo?, maxDepth: Int = MAX_DEPTH, action: (AccessibilityNodeInfo) -> Unit) {
        fun go(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > maxDepth || !node.isVisibleToUser) return
            action(node)
            for (i in 0 until node.childCount) go(node.getChild(i), depth + 1)
        }
        go(root, 0)
    }

    /** El primer elemento visible que cumple [match]. */
    fun find(root: AccessibilityNodeInfo?, maxDepth: Int = MAX_DEPTH, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun go(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > maxDepth || !node.isVisibleToUser) return null
            if (match(node)) return node
            for (i in 0 until node.childCount) go(node.getChild(i), depth + 1)?.let { return it }
            return null
        }
        return go(root, 0)
    }

    /** El primer campo de texto visible. */
    fun firstEditable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? = find(root) { it.isEditable }

    /** Toca el elemento (o el primer padre tocable); si no se puede, con un toque "con el dedo". */
    fun click(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        return Gestures.tap(service, node)
    }

    /** Reemplaza el texto del campo. true si la app lo aceptó. */
    fun setText(field: AccessibilityNodeInfo, text: String): Boolean {
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Espera hasta [timeoutMs] a que [find] encuentre algo en la ventana activa
     * (si [pkg] no es null, solo cuando esa app está en pantalla).
     */
    suspend fun waitFor(
        service: AccessibilityService,
        pkg: String?,
        timeoutMs: Long,
        pollMs: Long = 300,
        find: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?,
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = service.rootInActiveWindow
            if (root != null && (pkg == null || root.packageName?.toString() == pkg)) find(root)?.let { return it }
            delay(pollMs)
        }
        return null
    }

    private const val MAX_DEPTH = 40
}
