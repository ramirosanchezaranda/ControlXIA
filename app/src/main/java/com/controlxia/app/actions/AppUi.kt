package com.controlxia.app.actions

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.service.XiaAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Manejar cualquier app instalada sin el modelo, como lo haría una persona:
 * abrirla, tocar su lupa, escribir y buscar; tocar un botón por su nombre;
 * mandar un mensaje por una app de chat (Telegram, Messenger, Instagram,
 * Signal…) buscando el chat. Se guía por lo que la app anuncia a la
 * accesibilidad ("Buscar", "Enviar"), así que sirve en apps que no conoce.
 */
class AppUi(private val context: Context) {

    private val launcher by lazy { AppLauncher(context) }

    /** "Buscá pizza en PedidosYa". */
    suspend fun search(appName: String, query: String): String = withContext(Dispatchers.Default) {
        val service = XiaAccessibilityService.instance ?: return@withContext DeviceControl.NEEDS_A11Y
        val app = launcher.find(appName) ?: return@withContext "No encontré la app $appName."
        openApp(service, app, fresh = true)?.let { return@withContext it }
        val field = openSearch(service, app.pkg)
            ?: return@withContext fail("Abrí ${app.label} pero no encontré su buscador. Decí «mostrá los números» para elegirlo vos.")
        setText(field, query)
        delay(400)
        submit(service, app.pkg, field)
        AgentLog.add("APP busqué «$query» en ${app.label}")
        "Busqué «$query» en ${app.label}."
    }

    /** "En Spotify tocá Biblioteca": abre la app y toca lo que tenga ese nombre. */
    suspend fun tap(appName: String, label: String): String = withContext(Dispatchers.Default) {
        val service = XiaAccessibilityService.instance ?: return@withContext DeviceControl.NEEDS_A11Y
        val app = launcher.find(appName) ?: return@withContext "No encontré la app $appName."
        val alreadyThere = service.rootInActiveWindow?.packageName?.toString() == app.pkg
        if (!alreadyThere) openApp(service, app, fresh = false)?.let { return@withContext it }
        var found = waitFor(service, app.pkg, 5_000) { ScreenNumbers.findByLabel(label) }
        if (found == null) {
            // Puede estar más abajo.
            DeviceControl(context).scroll(DeviceControl.Direction.DOWN)
            delay(700)
            found = waitFor(service, app.pkg, 1_500) { ScreenNumbers.findByLabel(label) }
        }
        if (found == null) return@withContext fail("Abrí ${app.label} pero no encontré «$label».")
        ScreenNumbers.tapLabel(label)?.let { return@withContext it }
        AgentLog.add("APP toqué «$label» en ${app.label}")
        "Toqué «$label» en ${app.label}."
    }

    /**
     * Manda [text] al chat [chat] por [appName], buscándolo como lo haría una
     * persona. null si salió bien.
     */
    suspend fun sendMessage(appName: String, chat: String, text: String): String? = withContext(Dispatchers.Default) {
        val service = XiaAccessibilityService.instance ?: return@withContext DeviceControl.NEEDS_A11Y
        val app = launcher.find(appName) ?: return@withContext "No encontré la app $appName."
        if (!UnlockGate.unlock(context, "para usar ${app.label}")) return@withContext UnlockGate.NOT_UNLOCKED
        // Instagram: los chats están en la bandeja de mensajes, no en el inicio.
        val inbox = INBOXES[app.pkg]
        if (inbox != null) {
            val opened = runCatching {
                withContext(Dispatchers.Main) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(inbox)).setPackage(app.pkg)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    )
                }
            }.isSuccess
            if (!opened) openApp(service, app, fresh = true)?.let { return@withContext it }
        } else {
            openApp(service, app, fresh = true)?.let { return@withContext it }
        }
        val search = openSearch(service, app.pkg)
            ?: return@withContext fail("Abrí ${app.label} pero no encontré dónde buscar el chat.")
        setText(search, chat)
        delay(1_200)
        val row = waitFor(service, app.pkg, 4_000) { root -> chatResult(root, chat) }
            ?: return@withContext fail("No encontré a «$chat» en ${app.label}.")
        click(service, row)
        delay(900)
        val field = waitFor(service, app.pkg, 5_000) { root -> messageField(root) }
            ?: return@withContext fail("Abrí el chat en ${app.label} pero no encontré dónde escribir.")
        setText(field, text)
        delay(500)
        val send = waitFor(service, app.pkg, 3_000) { root -> sendButton(root) }
            ?: return@withContext fail("Escribí el mensaje en ${app.label} pero no encontré el botón de enviar: tocalo vos.")
        click(service, send)
        AgentLog.add("APP mensaje enviado por ${app.label} a «$chat»")
        null
    }

    // ---- Pasos ----

    /** Abre la app y espera a que esté en pantalla. null si salió bien. */
    private suspend fun openApp(service: XiaAccessibilityService, app: AppLauncher.App, fresh: Boolean): String? {
        if (!UnlockGate.unlock(context, "para abrir ${app.label}")) return UnlockGate.NOT_UNLOCKED
        val result = withContext(Dispatchers.Main) { launcher.open(app, fresh) }
        if (!result.startsWith("Abriendo")) return result
        return if (waitFor(service, app.pkg, 8_000) { it } == null) fail("${app.label} tarda en abrir.") else null
    }

    /** El campo de búsqueda listo para escribir (tocando la lupa si hace falta). */
    private suspend fun openSearch(service: XiaAccessibilityService, pkg: String): AccessibilityNodeInfo? {
        waitFor(service, pkg, 5_000) { root -> searchField(root) }?.let { field ->
            click(service, field)
            delay(500)
            return focusedEditable(service, pkg) ?: field
        }
        val button = waitFor(service, pkg, 2_000) { root -> searchButton(root) } ?: return null
        click(service, button)
        delay(800)
        return waitFor(service, pkg, 3_000) { root ->
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: searchField(root) ?: firstEditable(root)
        }
    }

    private fun focusedEditable(service: XiaAccessibilityService, pkg: String): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg } ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
    }

    /** Buscar: Enter del teclado; si la app no lo atiende, su botón "Buscar". */
    private suspend fun submit(service: XiaAccessibilityService, pkg: String, field: AccessibilityNodeInfo) {
        field.refresh()
        val entered = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        if (entered) return
        // Muchas apps buscan mientras escribís; si hay un botón "Buscar", tocarlo.
        service.rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }
            ?.let { root -> find(root) { !it.isEditable && it.isClickable && label(it).let { l -> l == "buscar" || l == "search" } } }
            ?.let { click(service, it) }
    }

    // ---- Qué es cada cosa en pantalla ----

    private fun searchField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        find(root) { it.isEditable && isSearchy(it) }

    private fun searchButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        find(root) { !it.isEditable && isSearchy(it) && label(it).length <= 40 }

    private fun isSearchy(node: AccessibilityNodeInfo): Boolean {
        val words = label(node) + " " + id(node)
        return SEARCH_WORDS.any { words.contains(it) }
    }

    /** El campo para escribir el mensaje: el que dice "Mensaje", si no el último editable. */
    private fun messageField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val editables = mutableListOf<AccessibilityNodeInfo>()
        visit(root) { if (it.isEditable) editables += it }
        return editables.firstOrNull { node -> MESSAGE_WORDS.any { (label(node) + " " + id(node)).contains(it) } }
            ?: editables.lastOrNull { !isSearchy(it) }
    }

    private fun sendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        find(root) { node ->
            !node.isEditable && (label(node) in SEND_LABELS || id(node).let { it.endsWith("send") || it.contains("send_button") })
        }

    /** El chat cuyo nombre más se parece a [chat] en los resultados (no el campo de búsqueda). */
    private fun chatResult(root: AccessibilityNodeInfo, chat: String): AccessibilityNodeInfo? {
        val wanted = WhatsAppUi.normalize(chat)
        var best: Pair<AccessibilityNodeInfo, Double>? = null
        visit(root) { node ->
            if (node.isEditable) return@visit
            val text = node.text?.toString() ?: node.contentDescription?.toString() ?: return@visit
            val clean = WhatsAppUi.normalize(text)
            if (clean.isEmpty() || clean.length > wanted.length + 40) return@visit
            if (SEARCH_WORDS.any { clean.startsWith(it) } || "result" in clean) return@visit
            val score = if (clean.contains(wanted)) 1.0 else WhatsAppUi.matchScore(chat, text)
            if (score >= MIN_SIMILARITY && (best == null || score > best!!.second)) best = node to score
        }
        return best?.first
    }

    // ---- Accesibilidad ----

    private fun label(node: AccessibilityNodeInfo): String {
        val hint = node.hintText?.toString().orEmpty()
        return WhatsAppUi.normalize(
            listOf(node.contentDescription?.toString().orEmpty(), node.text?.toString().orEmpty(), hint)
                .firstOrNull { it.isNotBlank() }.orEmpty(),
        )
    }

    private fun id(node: AccessibilityNodeInfo): String =
        node.viewIdResourceName?.substringAfter(":id/")?.lowercase().orEmpty()

    private fun find(root: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        visit(root) { if (found == null && match(it)) found = it }
        return found
    }

    private fun visit(root: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Unit) {
        fun go(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 40 || !node.isVisibleToUser) return
            action(node)
            for (i in 0 until node.childCount) go(node.getChild(i), depth + 1)
        }
        go(root, 0)
    }

    private fun firstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? = find(root) { it.isEditable }

    private suspend fun waitFor(
        service: XiaAccessibilityService,
        pkg: String,
        timeoutMs: Long,
        find: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?,
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = service.rootInActiveWindow
            if (root != null && root.packageName?.toString() == pkg) find(root)?.let { return it }
            delay(300)
        }
        return null
    }

    private fun setText(field: AccessibilityNodeInfo, text: String) {
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Toca el elemento (o el primer padre tocable); si no se puede, con un gesto. */
    private fun click(service: XiaAccessibilityService, node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (!bounds.isEmpty) Gestures.tapAt(service, bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun fail(reason: String): String {
        AgentLog.add("APP $reason")
        return reason
    }

    private companion object {
        val SEARCH_WORDS = listOf("buscar", "busca", "search", "lupa", "buscador")
        val MESSAGE_WORDS = listOf("mensaje", "message", "escrib", "aa", "compose", "input")
        val SEND_LABELS = setOf("enviar", "send", "enviar mensaje", "send message")
        val INBOXES = mapOf("com.instagram.android" to "https://www.instagram.com/direct/inbox/")
        const val MIN_SIMILARITY = 0.6
    }
}
