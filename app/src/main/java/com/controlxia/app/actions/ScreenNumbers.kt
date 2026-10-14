package com.controlxia.app.actions

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.service.XiaAccessibilityService
import java.text.Normalizer
import java.util.Locale

/**
 * Manejar cualquier pantalla con la voz, como el Acceso por voz de Google:
 * "mostrá los números" dibuja un número sobre cada cosa tocable, y "tocá 5",
 * "mantené apretado 5" o "tocá Guardar" actúan sin pasar por el modelo.
 * También escribe en el campo de texto que tenga el foco.
 *
 * Los números se dibujan en una ventana del servicio de accesibilidad (no
 * necesita permisos extra), no se pueden tocar, y se actualizan solos cuando
 * cambia la pantalla mientras están visibles.
 */
object ScreenNumbers {

    private val main = Handler(Looper.getMainLooper())
    private var overlay: NumbersView? = null

    @Volatile
    private var items: List<AccessibilityNodeInfo> = emptyList()

    val visible: Boolean get() = overlay != null

    /** Muestra (o actualiza) los números. null si salió bien. */
    fun show(): String? {
        val service = XiaAccessibilityService.instance ?: return DeviceControl.NEEDS_A11Y
        val root = service.rootInActiveWindow ?: return "No pude leer la pantalla."
        val found = collect(root)
        if (found.isEmpty()) return "No veo nada para tocar en esta pantalla."
        items = found
        val boxes = found.map { node -> Rect().also { node.getBoundsInScreen(it) } }
        main.post { draw(service, boxes) }
        AgentLog.add("NÚMEROS ${found.size} en pantalla")
        return null
    }

    fun hide() {
        items = emptyList()
        main.post {
            overlay?.let { view -> runCatching { windowManager(view.context)?.removeView(view) } }
            overlay = null
        }
    }

    /** La pantalla cambió: si los números están a la vista, se vuelven a calcular. */
    fun onScreenChanged() {
        if (!visible) return
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, REFRESH_DELAY_MS)
    }

    private val refresh = Runnable { if (visible) show() }

    /** Toca el elemento [number]. null si salió bien. */
    fun tap(number: Int, long: Boolean = false): String? {
        val service = XiaAccessibilityService.instance ?: return DeviceControl.NEEDS_A11Y
        val node = items.getOrNull(number - 1)
            ?: return if (items.isEmpty()) "Primero decí «mostrá los números»." else "No hay un $number en pantalla."
        node.refresh()
        val ok = if (long) Gestures.longPress(service, node) else Nodes.click(service, node)
        AgentLog.add("NÚMEROS ${if (long) "mantener" else "tocar"} $number ${if (ok) "ok" else "falló"}")
        onScreenChanged()
        return if (ok) null else "No pude tocar el $number."
    }

    /**
     * Toca lo que diga [label] ("Guardar", "Enviar", "Reels"): primero lo que
     * coincide exacto, después lo que empieza o contiene ese texto.
     */
    fun tapLabel(label: String, long: Boolean = false): String? {
        val service = XiaAccessibilityService.instance ?: return DeviceControl.NEEDS_A11Y
        val node = findByLabel(label)
            ?: return "No veo «$label» en pantalla. Decí «mostrá los números» y elegí uno."
        val ok = if (long) Gestures.longPress(service, node) else Nodes.click(service, node)
        AgentLog.add("TOCAR «$label» ${if (ok) "ok" else "falló"}")
        onScreenChanged()
        return if (ok) null else "No pude tocar «$label»."
    }

    /** Lo tocable que dice [label], o null si no está en pantalla. */
    fun findByLabel(label: String): AccessibilityNodeInfo? {
        val root = XiaAccessibilityService.instance?.rootInActiveWindow ?: return null
        val wanted = normalize(label)
        if (wanted.isEmpty()) return null
        val candidates = collect(root).map { it to normalize(labelOf(it)) }.filter { it.second.isNotEmpty() }
        return candidates.firstOrNull { it.second == wanted }?.first
            ?: candidates.firstOrNull { it.second.startsWith(wanted) }?.first
            ?: candidates.firstOrNull { wanted in it.second }?.first
    }

    /** Si hay un campo de texto con el foco (el teclado abierto), para dictar ahí. */
    fun hasFocusedField(): Boolean =
        XiaAccessibilityService.instance?.rootInActiveWindow
            ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable == true

    /** Escribe [text] en el campo con el foco (o el primero de la pantalla). */
    fun type(text: String, replace: Boolean = false): String? {
        val field = focusedField() ?: return "No hay ningún campo de texto para escribir."
        val current = if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
        val value = when {
            replace || current.isEmpty() -> text
            current.endsWith(" ") -> current + text
            else -> "$current $text"
        }
        return if (Nodes.setText(field, value)) null else "No pude escribir ahí."
    }

    /** Apretar Enter en el campo con el foco (buscar, enviar en algunos chats). */
    fun enter(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "Esta versión de Android no lo permite."
        val field = focusedField() ?: return "No hay ningún campo de texto."
        val ok = field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        return if (ok) null else "No pude apretar Enter."
    }

    private fun focusedField(): AccessibilityNodeInfo? {
        val root = XiaAccessibilityService.instance?.rootInActiveWindow ?: return null
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }?.let { return it }
        fun first(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > 40) return null
            if (node.isEditable && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) first(node.getChild(i), depth + 1)?.let { return it }
            return null
        }
        return first(root, 0)
    }

    /** Lo tocable y escribible de la pantalla, en orden de lectura. */
    private fun collect(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val found = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 40 || found.size >= MAX_ITEMS) return
            if (!node.isVisibleToUser) return
            if (node.isClickable || node.isEditable || node.isLongClickable || node.isCheckable) {
                val box = Rect().also { node.getBoundsInScreen(it) }
                if (box.width() > 8 && box.height() > 8 && found.none { it.second == box }) found += node to box
            }
            for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1)
        }
        visit(root, 0)
        // De arriba a abajo y de izquierda a derecha, en filas de ~1/20 de pantalla.
        return found.sortedWith(compareBy({ it.second.top / ROW_PX }, { it.second.left })).map { it.first }
    }

    private fun labelOf(node: AccessibilityNodeInfo): String {
        val own = node.text?.toString().orEmpty().ifBlank { node.contentDescription?.toString().orEmpty() }
        if (own.isNotBlank()) return own
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val text = child.text?.toString().orEmpty().ifBlank { child.contentDescription?.toString().orEmpty() }
            if (text.isNotBlank()) return text
        }
        return ""
    }

    private fun windowManager(context: Context): WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private fun draw(service: XiaAccessibilityService, boxes: List<Rect>) {
        val existing = overlay
        if (existing != null) {
            existing.boxes = boxes
            existing.invalidate()
            return
        }
        val view = NumbersView(service).apply { this.boxes = boxes }
        @Suppress("DEPRECATION")
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching {
            windowManager(service)?.addView(view, params)
            overlay = view
        }.onFailure { AgentLog.add("NÚMEROS no pude dibujarlos: ${it.message}") }
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale("es")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9ñ ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Dibuja una etiqueta con el número en la esquina de cada elemento. */
    @SuppressLint("ViewConstructor")
    private class NumbersView(context: Context) : View(context) {
        var boxes: List<Rect> = emptyList()
        private val origin = IntArray(2)
        private val badge = RectF()
        private val density = context.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E61B1B1F") }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7186F2")
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13f * density
            isFakeBoldText = true
        }

        override fun onDraw(canvas: Canvas) {
            // Las coordenadas son de pantalla; la ventana puede no empezar en 0,0.
            getLocationOnScreen(origin)
            boxes.forEachIndexed { index, box ->
                val label = (index + 1).toString()
                val w = text.measureText(label) + 10 * density
                val h = 20 * density
                val x = (box.left - origin[0]).toFloat()
                val y = (box.top - origin[1]).toFloat()
                badge.set(x, y, x + w, y + h)
                canvas.drawRoundRect(badge, 6 * density, 6 * density, fill)
                canvas.drawRoundRect(badge, 6 * density, 6 * density, border)
                canvas.drawText(label, x + 5 * density, y + h - 5.5f * density, text)
            }
        }
    }

    private const val MAX_ITEMS = 80
    private const val ROW_PX = 60
    private const val REFRESH_DELAY_MS = 700L
}
