package com.controlxia.app.actions

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.controlxia.app.service.XiaAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

/**
 * Lee y maneja la pantalla de cualquier app a través del servicio de
 * accesibilidad. La pantalla se le muestra al modelo como una lista numerada
 * de elementos ("[3] botón «Buscar»"); las acciones usan esos números.
 *
 * Los números valen hasta la próxima lectura. Después de cada acción se espera
 * a que la app reaccione y se devuelve la pantalla nueva, así el modelo ve el
 * resultado sin gastar un paso extra.
 */
class ScreenControl(private val context: Context) {

    class Element(val id: Int, val kind: String, val label: String, val node: AccessibilityNodeInfo)

    @Volatile
    private var elements: List<Element> = emptyList()

    /** Devuelve la pantalla actual como texto para el modelo. */
    suspend fun read(): String = withContext(Dispatchers.Default) {
        unavailableReason()?.let { return@withContext it }
        val service = XiaAccessibilityService.instance!!
        delay(300)
        val root = service.rootInActiveWindow
            ?: return@withContext "No pude leer la pantalla (puede estar cargando). Volvé a intentar."
        val found = mutableListOf<Element>()
        var scrollable = false
        fun visit(node: AccessibilityNodeInfo?, depth: Int, covered: Boolean) {
            if (node == null || depth > MAX_DEPTH || found.size >= MAX_ELEMENTS) return
            if (!node.isVisibleToUser) return
            if (node.isScrollable) scrollable = true
            val own = ownText(node)
            var coverChildren = covered
            when {
                node.isEditable -> found += Element(found.size + 1, "campo", own.ifBlank { idName(node) }, node)
                node.isClickable -> {
                    val label = own.ifBlank { descendantText(node) }.ifBlank { idName(node) }
                    val kind = when {
                        node.isCheckable -> if (node.isChecked) "casilla marcada" else "casilla"
                        node.className?.contains("Button") == true -> "botón"
                        else -> "tocable"
                    }
                    if (label.isNotBlank()) found += Element(found.size + 1, kind, label, node)
                    coverChildren = true
                }
                own.isNotBlank() && !covered -> found += Element(found.size + 1, "texto", own, node)
            }
            for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1, coverChildren)
        }
        visit(root, 0, false)
        elements = found

        val text = StringBuilder("PANTALLA — app: ${appLabel(root.packageName?.toString())}\n")
        for (element in found) {
            val line = "[${element.id}] ${element.kind} «${element.label}»\n"
            if (text.length + line.length > MAX_CHARS) {
                text.append("(…hay más elementos)\n")
                break
            }
            text.append(line)
        }
        if (found.isEmpty()) text.append("(no hay elementos legibles; puede estar cargando)\n")
        if (scrollable) text.append("Hay más contenido: usá screen_scroll para verlo.\n")
        text.toString()
    }

    /** El elemento [id] de la última lectura, o null. */
    fun element(id: Int): Element? = elements.getOrNull(id - 1)

    /**
     * Si tocar [id] puede tener consecuencias (enviar, comprar, pagar, borrar…),
     * devuelve su texto para pedir confirmación antes.
     */
    fun riskyLabel(id: Int): String? {
        val label = element(id)?.label ?: return null
        return label.takeIf { RISKY.containsMatchIn(normalize(it)) }
    }

    suspend fun tap(id: Int): String = withContext(Dispatchers.Default) {
        unavailableReason()?.let { return@withContext it }
        val element = element(id) ?: return@withContext missing(id)
        if (!click(element.node)) return@withContext "No pude tocar [$id] «${element.label}»."
        delay(SETTLE_MS)
        "Toqué «${element.label}».\n" + read()
    }

    suspend fun type(id: Int, text: String, submit: Boolean): String = withContext(Dispatchers.Default) {
        unavailableReason()?.let { return@withContext it }
        val element = element(id) ?: return@withContext missing(id)
        var field = findEditable(element.node)
        if (field == null) {
            // Algunas barras de búsqueda se vuelven editables recién al tocarlas.
            click(element.node)
            delay(800)
            field = XiaAccessibilityService.instance?.rootInActiveWindow
                ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.takeIf { it.isEditable }
        }
        if (field == null) return@withContext "[$id] no es un campo de texto."
        if (!Nodes.setText(field, text)) {
            return@withContext "No pude escribir en [$id]."
        }
        var submitted = false
        if (submit && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            submitted = field.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            )
        }
        delay(SETTLE_MS)
        val note = when {
            !submit -> ""
            submitted -> " y lo envié con Enter"
            else -> " (no pude apretar Enter: tocá el botón de buscar/enviar)"
        }
        "Escribí «$text»$note.\n" + read()
    }

    suspend fun scroll(direction: String): String = withContext(Dispatchers.Default) {
        unavailableReason()?.let { return@withContext it }
        val service = XiaAccessibilityService.instance!!
        val forward = direction != "up"
        val target = service.rootInActiveWindow?.let { largestScrollable(it) }
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val ok = target?.performAction(action) == true || swipe(service, forward)
        delay(SETTLE_MS)
        (if (ok) "Desplacé la pantalla.\n" else "No pude desplazar.\n") + read()
    }

    suspend fun back(): String = withContext(Dispatchers.Default) {
        unavailableReason()?.let { return@withContext it }
        XiaAccessibilityService.instance!!.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        delay(SETTLE_MS)
        "Volví atrás.\n" + read()
    }

    private fun unavailableReason(): String? {
        if (XiaAccessibilityService.instance == null) {
            return if (XiaAccessibilityService.isEnabled(context)) {
                "El control de apps se está iniciando; probá de nuevo en un segundo."
            } else {
                "El control de apps (accesibilidad) no está activado. Decile al usuario que lo " +
                    "active en ControlXIA → Sistema → Control de apps."
            }
        }
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguard.isKeyguardLocked) return "El teléfono está bloqueado: hay que desbloquearlo primero."
        return null
    }

    private fun missing(id: Int) = "No existe el elemento [$id]: leé la pantalla de nuevo con screen_read."

    private fun click(node: AccessibilityNodeInfo): Boolean {
        node.refresh()
        val service = XiaAccessibilityService.instance ?: return false
        return Nodes.click(service, node)
    }

    private fun swipe(service: AccessibilityService, forward: Boolean): Boolean {
        val metrics = context.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val (from, to) = if (forward) 0.75f to 0.30f else 0.30f to 0.75f
        return gesture(service, x, metrics.heightPixels * from, metrics.heightPixels * to, 350)
    }

    private fun gesture(service: AccessibilityService, x: Float, y: Float, toY: Float?, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            if (toY != null) lineTo(x, toY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            findEditable(node.getChild(i) ?: continue)?.let { return it }
        }
        return null
    }

    private fun largestScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return
            if (node.isScrollable && node.isVisibleToUser) {
                val r = Rect().also { node.getBoundsInScreen(it) }
                val area = r.width() * r.height()
                if (area > bestArea) {
                    best = node
                    bestArea = area
                }
            }
            for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1)
        }
        visit(root, 0)
        return best
    }

    private fun ownText(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val hint = node.hintText?.toString().orEmpty()
        val value = text.ifBlank { desc }.ifBlank { if (node.isEditable) hint else "" }
        return clean(value)
    }

    /** Textos de los hijos de un elemento tocable sin texto propio (ej. una tarjeta de producto). */
    private fun descendantText(node: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        fun visit(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 8 || parts.size >= 5) return
            val own = if (n === node) "" else ownText(n)
            if (own.isNotBlank() && own !in parts) parts += own
            for (i in 0 until n.childCount) visit(n.getChild(i), depth + 1)
        }
        visit(node, 0)
        return clean(parts.joinToString(" · "))
    }

    private fun idName(node: AccessibilityNodeInfo): String =
        node.viewIdResourceName?.substringAfter(":id/")?.replace('_', ' ').orEmpty()

    private fun clean(value: String): String =
        value.replace(Regex("\\s+"), " ").trim().take(MAX_LABEL)

    private fun appLabel(pkg: String?): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg!!, 0)).toString()
    }.getOrDefault(pkg ?: "desconocida")

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale("es")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    private companion object {
        const val MAX_DEPTH = 40
        const val MAX_ELEMENTS = 90
        // Las pantallas viajan al modelo en cada paso: con Groq hay límite de
        // tokens por minuto, así que se mantienen compactas.
        const val MAX_CHARS = 2_500
        const val MAX_LABEL = 90
        const val SETTLE_MS = 1_200L

        /** Botones que mandan, compran, pagan o borran algo: piden confirmación. */
        val RISKY = Regex(
            "\\b(enviar|envialo|send|comprar|compra|pagar|pago|confirmar|finalizar|publicar|" +
                "eliminar|borrar|transferir|suscribirme|suscribir|buy|pay|delete|post|checkout)\\b"
        )
    }
}
