package com.controlxia.app.actions

import android.view.accessibility.AccessibilityNodeInfo
import com.controlxia.app.service.XiaAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

/**
 * El chat que está abierto (WhatsApp, Telegram, Messenger…): escribir en el
 * campo del mensaje y tocar "Enviar". Mandar lo hace la app recién después
 * del "sí" del usuario (ver TaskPlanner y PlanRunner).
 */
object ChatUi {

    /** Escribe [text] en el campo del mensaje. null si salió bien; si no, por qué. */
    suspend fun write(text: String): String? = withContext(Dispatchers.Default) {
        val service = XiaAccessibilityService.instance ?: return@withContext DeviceControl.NEEDS_A11Y
        val field = Nodes.waitFor(service, null, WAIT_MS) { root -> messageField(root) }
            ?: return@withContext "No encontré dónde escribir en el chat."
        if (!Nodes.setText(field, text)) return@withContext "No pude escribir en el chat."
        delay(SETTLE_MS)
        null
    }

    /** Toca "Enviar". null si salió bien; si no, por qué. */
    suspend fun send(): String? = withContext(Dispatchers.Default) {
        val service = XiaAccessibilityService.instance ?: return@withContext DeviceControl.NEEDS_A11Y
        val button = Nodes.waitFor(service, null, WAIT_MS) { root -> sendButton(root) }
            ?: return@withContext "Escribí el mensaje pero no encontré el botón de enviar: tocalo vos."
        Nodes.click(service, button)
        delay(SETTLE_MS)
        null
    }

    /** El campo del mensaje: el de WhatsApp por su id, si no el que dice "Mensaje", si no el último editable. */
    private fun messageField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val pkg = root.packageName?.toString().orEmpty()
        root.findAccessibilityNodeInfosByViewId("$pkg:id/entry").firstOrNull { it.isVisibleToUser }?.let { return it }
        val editables = mutableListOf<AccessibilityNodeInfo>()
        Nodes.visit(root) { if (it.isEditable) editables += it }
        return editables.firstOrNull { MESSAGE.containsMatchIn(describe(it)) } ?: editables.lastOrNull { !SEARCH.containsMatchIn(describe(it)) }
    }

    private fun sendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val pkg = root.packageName?.toString().orEmpty()
        root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull { it.isVisibleToUser }?.let { return it }
        return Nodes.find(root) { node ->
            val text = describe(node)
            !node.isEditable && text.length <= 40 && SEND.containsMatchIn(text)
        }
    }

    private fun describe(node: AccessibilityNodeInfo): String = plain(
        listOf(node.contentDescription, node.text, node.hintText, node.viewIdResourceName?.substringAfter(":id/"))
            .joinToString(" ") { it?.toString().orEmpty() },
    )

    private val MESSAGE = Regex("\\b(?:mensaje|message|escrib|compose|entry)")
    private val SEARCH = Regex("\\b(?:buscar|search)")
    private val SEND = Regex("^(?:enviar|send)\\b")

    private fun plain(text: String): String =
        Normalizer.normalize(text.lowercase(Locale("es")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    private const val WAIT_MS = 4_000L
    private const val SETTLE_MS = 500L
}
