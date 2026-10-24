package com.controlxia.app.actions

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.permissions.PermissionManager
import com.controlxia.app.service.XiaAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

/**
 * Maneja WhatsApp por nombre de chat, con el control de apps: sirve para grupos
 * (que no tienen número) y para contactos cuyo número no se puede armar.
 *
 * Hace en un solo paso lo que al modelo le llevaría seis (abrir, buscar,
 * escribir el nombre, entrar, escribir, enviar), que con Groq gratis es la
 * diferencia entre que ande o que se pase del límite de tokens por minuto.
 * Cada paso queda en el registro del agente para ver dónde se traba.
 */
class WhatsAppUi(private val context: Context) {

    /** Cómo terminó [openChat]: el nombre real del chat abierto, o el motivo del fallo. */
    sealed interface OpenResult {
        data class Opened(val title: String) : OpenResult
        data class Failed(val reason: String) : OpenResult
    }

    /**
     * Abre el chat o grupo [name]. Si no aparece tal cual y [fuzzy] está activo,
     * elige el chat de nombre más parecido: el reconocimiento de voz a veces
     * entiende "fulgo 2" en vez de "fútbol 2".
     */
    suspend fun openChat(name: String, fuzzy: Boolean = true): OpenResult = withContext(Dispatchers.Default) {
        val service = XiaAccessibilityService.instance
            ?: return@withContext OpenResult.Failed("Para entrar a chats de WhatsApp necesito el control de apps activado.")
        if (!UnlockGate.unlock(context, "para usar WhatsApp")) return@withContext OpenResult.Failed(UnlockGate.NOT_UNLOCKED)
        if (!PermissionManager.canLaunchActivities(context)) {
            return@withContext OpenResult.Failed("Para abrir WhatsApp necesito el permiso Mostrar sobre otras apps.")
        }
        val pkg = PACKAGES.firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }
            ?: return@withContext OpenResult.Failed("No encontré WhatsApp instalado.")

        // Arranca en la pantalla principal (no en el último chat abierto).
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        withContext(Dispatchers.Main) { context.startActivity(launch) }
        AgentLog.add("WHATSAPP buscar chat «$name»")

        val search = Nodes.waitFor(service, pkg, 6_000) { findSearch(it, pkg) }
            ?: return@withContext failed("No encontré el buscador de WhatsApp.")
        Nodes.click(service, search)

        if (!typeSearch(service, pkg, name)) return@withContext failed("No pude escribir en el buscador de WhatsApp.")
        var result = Nodes.waitFor(service, pkg, 3_000) { findResult(it, pkg, name) }
        if (result == null && fuzzy && normalize(name).length >= 3) {
            // Con las primeras letras de la palabra más larga ("fu" de "fulgo")
            // WhatsApp lista los chats parecidos.
            val longest = normalize(name).split(' ').maxByOrNull { it.length }.orEmpty()
            typeSearch(service, pkg, longest.take(2))
            result = Nodes.waitFor(service, pkg, 3_000) { root ->
                closestResult(root, pkg, name)?.takeIf { it.second >= MIN_SIMILARITY }?.first
            }
            val best = result?.text ?: service.rootInActiveWindow?.let { closestResult(it, pkg, name) }
                ?.let { "${it.first.text} (${(it.second * 100).toInt()}%, no alcanza)" }
            AgentLog.add("WHATSAPP «$name» no está tal cual; el más parecido: ${best ?: "ninguno"}")
        }
        if (result == null) return@withContext failed("No encontré «$name» en WhatsApp.")
        val title = result.text?.toString()?.trim().orEmpty().ifBlank { name }
        Nodes.click(service, result)

        Nodes.waitFor(service, pkg, 4_000) { findEntry(it, pkg) }
            ?: return@withContext failed("Encontré «$title» pero no pude entrar al chat.")
        AgentLog.add("WHATSAPP chat «$title» abierto")
        OpenResult.Opened(title)
    }

    /**
     * Escribe y envía [text] en el chat [title]. Si ya no está en pantalla (el
     * usuario tardó en confirmar o tocó otra cosa) lo vuelve a abrir. null si salió bien.
     */
    suspend fun sendToChat(title: String, text: String): String? = withContext(Dispatchers.Default) {
        val service = XiaAccessibilityService.instance
            ?: return@withContext "Para enviar necesito el control de apps activado."
        val root = service.rootInActiveWindow
        val pkg = root?.packageName?.toString()
        val onChat = pkg in PACKAGES && findEntry(root!!, pkg!!) != null &&
            chatHeader(root, pkg)?.let { normalize(it) == normalize(title) } != false
        if (!onChat) {
            AgentLog.add("WHATSAPP el chat ya no está en pantalla: lo vuelvo a abrir")
            val reopened = openChat(title, fuzzy = false)
            if (reopened is OpenResult.Failed) return@withContext reopened.reason
        }
        val current = service.rootInActiveWindow?.packageName?.toString()?.takeIf { it in PACKAGES }
            ?: return@withContext fail("WhatsApp no está en pantalla.")
        val entry = Nodes.waitFor(service, current, 2_000) { findEntry(it, current) }
            ?: return@withContext fail("No encontré dónde escribir en el chat.")
        Nodes.setText(entry, text)
        delay(500)
        val send = Nodes.waitFor(service, current, 2_000) { findSendButton(it, current) }
            ?: return@withContext fail("Escribí el mensaje pero no encontré el botón de enviar: tocalo vos.")
        Nodes.click(service, send)
        AgentLog.add("WHATSAPP enviado")
        null
    }

    private fun failed(reason: String) = OpenResult.Failed(fail(reason))

    /**
     * Manda un archivo (un audio) a un chat. Con [number] va directo a ese
     * chat; si no (grupos, gente sin número en la agenda), se elige [chatName]
     * en la pantalla "Enviar a…" de WhatsApp. null si salió bien.
     */
    suspend fun sendFile(file: java.io.File, mime: String, number: String?, chatName: String): String? =
        withContext(Dispatchers.Default) {
            val service = XiaAccessibilityService.instance
                ?: return@withContext "Para mandarlo necesito el control de apps activado."
            if (!UnlockGate.unlock(context, "para mandarlo por WhatsApp")) return@withContext UnlockGate.NOT_UNLOCKED
            val pkg = PACKAGES.firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }
                ?: return@withContext "No encontré WhatsApp instalado."
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val share = Intent(Intent.ACTION_SEND)
                .setType(mime)
                .setPackage(pkg)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            share.clipData = android.content.ClipData.newRawUri("audio", uri)
            if (number != null) share.putExtra("jid", "$number@s.whatsapp.net")
            AgentLog.add("WHATSAPP compartir ${file.name} con ${number ?: "«$chatName»"}")
            val opened = runCatching { withContext(Dispatchers.Main) { context.startActivity(share) } }.isSuccess
            if (!opened) return@withContext fail("No pude pasarle el audio a WhatsApp.")

            if (number == null) {
                // "Enviar a…": buscar el chat, marcarlo y tocar enviar.
                val search = Nodes.waitFor(service, pkg, 6_000) { findSearch(it, pkg) }
                    ?: return@withContext fail("No encontré el buscador de «Enviar a» en WhatsApp.")
                Nodes.click(service, search)
                if (!typeSearch(service, pkg, chatName)) return@withContext fail("No pude buscar «$chatName».")
                val row = Nodes.waitFor(service, pkg, 3_000) { findResult(it, pkg, chatName) }
                    ?: Nodes.waitFor(service, pkg, 2_000) { root ->
                        closestResult(root, pkg, chatName)?.takeIf { it.second >= MIN_SIMILARITY }?.first
                    }
                    ?: return@withContext fail("No encontré «$chatName» para mandarle el audio.")
                Nodes.click(service, row)
                delay(600)
            }
            // El botón de enviar, y si WhatsApp pregunta "¿Enviar audio a…?", confirmar.
            val deadline = System.currentTimeMillis() + 8_000
            var sent = false
            while (System.currentTimeMillis() < deadline && !sent) {
                val root = service.rootInActiveWindow
                if (root != null && root.packageName?.toString() == pkg) {
                    val button = findSendButton(root, pkg) ?: findDialogButton(root)
                    if (button != null) {
                        Nodes.click(service, button)
                        sent = true
                        delay(800)
                        // Algunas versiones piden confirmar después del primer toque.
                        service.rootInActiveWindow?.let { findDialogButton(it) }?.let { Nodes.click(service, it) }
                    }
                }
                if (!sent) delay(300)
            }
            if (!sent) return@withContext fail("Dejé el audio listo en WhatsApp, pero no encontré el botón de enviar: tocalo vos.")
            AgentLog.add("WHATSAPP audio enviado")
            null
        }

    /** El "Enviar" de un diálogo de confirmación. */
    private fun findDialogButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        listOf("android:id/button1").firstNotNullOfOrNull { id ->
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser }
        } ?: listOf("ENVIAR", "Enviar", "SEND", "Send").firstNotNullOfOrNull { label ->
            root.findAccessibilityNodeInfosByText(label).firstOrNull {
                it.isVisibleToUser && it.isClickable && it.text?.toString().equals(label, ignoreCase = true)
            }
        }

    private fun fail(reason: String): String {
        AgentLog.add("WHATSAPP $reason")
        return reason
    }

    private fun findSearch(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        SEARCH_IDS.forEach { id ->
            root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").firstOrNull { it.isVisibleToUser }
                ?.let { return it }
        }
        // Lupa o barra "Preguntá a Meta AI o buscá": por descripción o texto.
        return SEARCH_LABELS.firstNotNullOfOrNull { label ->
            root.findAccessibilityNodeInfosByText(label).firstOrNull { it.isVisibleToUser }
        }
    }

    /**
     * El primer resultado cuyo nombre contiene lo buscado, sin importar tildes
     * ni mayúsculas ("futbol 2" encuentra "Fútbol 2 ⚽"). Los chats aparecen
     * antes que los mensajes en los resultados, así que el primero es el chat.
     */
    private fun findResult(root: AccessibilityNodeInfo, pkg: String, name: String): AccessibilityNodeInfo? {
        val query = normalize(name)
        root.findAccessibilityNodeInfosByViewId("$pkg:id/conversations_row_contact_name")
            .firstOrNull { normalize(it.text?.toString().orEmpty()).contains(query) }
            ?.let { return it }
        var found: AccessibilityNodeInfo? = null
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || found != null || depth > 30 || !node.isVisibleToUser) return
            val text = node.text?.toString().orEmpty()
            val clean = normalize(text)
            // "No hay resultados para «x»" también contiene lo buscado.
            if (!node.isEditable && clean.contains(query) && "result" !in clean &&
                clean.length <= query.length + 30
            ) {
                found = node
                return
            }
            for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1)
        }
        visit(root, 0)
        return found
    }

    /** Escribe [query] en el buscador abierto. */
    private suspend fun typeSearch(service: XiaAccessibilityService, pkg: String, query: String): Boolean {
        val field = Nodes.waitFor(service, pkg, 3_000) { root ->
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: Nodes.firstEditable(root)
        } ?: return false
        Nodes.setText(field, query)
        delay(1_000)
        return true
    }

    /** El chat de nombre más parecido a [name], con su puntaje (0 a 1). */
    private fun closestResult(
        root: AccessibilityNodeInfo,
        pkg: String,
        name: String,
    ): Pair<AccessibilityNodeInfo, Double>? =
        root.findAccessibilityNodeInfosByViewId("$pkg:id/conversations_row_contact_name")
            .filter { !it.text.isNullOrBlank() }
            .map { it to matchScore(name, it.text.toString()) }
            .maxByOrNull { it.second }

    /**
     * El chat de WhatsApp que está abierto adelante, o null si no hay uno. Busca
     * en todas las ventanas (un aviso encima puede tener el foco) y, si cambió
     * el nombre interno del encabezado, cualquier "…contact_name" de WhatsApp.
     */
    fun openChatTitle(): String? {
        val service = XiaAccessibilityService.instance ?: return null
        val roots = listOfNotNull(service.rootInActiveWindow) +
            runCatching { service.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
        for (root in roots) {
            val pkg = root.packageName?.toString()?.takeIf { it in PACKAGES } ?: continue
            chatHeader(root, pkg)?.let { return it }
            anyContactName(root)?.let { return it }
        }
        return null
    }

    private fun anyContactName(root: AccessibilityNodeInfo): String? {
        fun visit(node: AccessibilityNodeInfo?, depth: Int): String? {
            if (node == null || depth > 12) return null
            val id = node.viewIdResourceName.orEmpty()
            if (id.endsWith("contact_name") && !node.text.isNullOrBlank()) return node.text.toString()
            for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1)?.let { return it }
            return null
        }
        return visit(root, 0)
    }

    private fun chatHeader(root: AccessibilityNodeInfo, pkg: String): String? =
        root.findAccessibilityNodeInfosByViewId("$pkg:id/conversation_contact_name")
            .firstOrNull()?.text?.toString()?.takeIf { it.isNotBlank() }

    private fun findEntry(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByViewId("$pkg:id/entry").firstOrNull()
            ?: Nodes.firstEditable(root)?.takeIf { field ->
                val hint = field.hintText?.toString().orEmpty().lowercase()
                "mensaje" in hint || "message" in hint
            }

    private fun findSendButton(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull()
            ?: listOf("Enviar", "Send").firstNotNullOfOrNull { label ->
                root.findAccessibilityNodeInfosByText(label).firstOrNull {
                    it.contentDescription?.toString().equals(label, ignoreCase = true)
                }
            }

    companion object {
        val PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
        private val SEARCH_IDS = listOf("menuitem_search", "my_search_bar", "search_bar", "search_icon")
        private val SEARCH_LABELS = listOf("Buscar", "buscá", "Search")
        private const val MIN_SIMILARITY = 0.6

        /** "el grupo de la Familia" → "Familia": WhatsApp busca por el nombre del chat. */
        fun chatName(raw: String): String =
            raw.trim()
                .replace(Regex("^(al|el|a|en el)\\s+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^grupo\\s+(de\\s+)?", RegexOption.IGNORE_CASE), "")
                // "la familia" → "familia": WhatsApp no encuentra por el artículo.
                .replace(Regex("^(la|las|los|el)\\s+(?=\\S)", RegexOption.IGNORE_CASE), "")
                .trim()

        /** Sin tildes, mayúsculas ni emojis, y números en cifras: "Fútbol cinco ⚽" → "futbol 5". */
        fun normalize(text: String): String =
            Normalizer.normalize(text.lowercase(Locale("es")), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[^a-z0-9ñ ]"), " ")
                .split(' ')
                .filter { it.isNotEmpty() }
                .joinToString(" ") { NUMBERS[it] ?: it }

        private val NUMBERS = listOf(
            "cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez",
            "once", "doce",
        ).withIndex().associate { (n, word) -> word to n.toString() }

        /**
         * Qué tanto se parece lo que dijo el usuario a un nombre de chat, comparando
         * también contra cada tramo del nombre con la misma cantidad de palabras:
         * "fulgo 2" contra "Fútbol 2 los pibes" mira "futbol 2".
         */
        fun matchScore(spoken: String, chat: String): Double {
            val words = normalize(chat).split(' ').filter { it.isNotEmpty() }
            val size = normalize(spoken).split(' ').count { it.isNotEmpty() }
            val windows = if (size == 0 || words.size <= size) emptyList() else words.windowed(size) { it.joinToString(" ") }
            return (windows + normalize(chat)).maxOf { similarity(spoken, it) }
        }

        /** 1 = iguales, 0 = nada que ver (distancia de edición sobre el texto normalizado). */
        fun similarity(a: String, b: String): Double {
            val x = normalize(a)
            val y = normalize(b)
            if (x.isEmpty() || y.isEmpty()) return 0.0
            var prev = IntArray(y.length + 1) { it }
            for (i in 1..x.length) {
                val cur = IntArray(y.length + 1)
                cur[0] = i
                for (j in 1..y.length) {
                    val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                }
                prev = cur
            }
            return 1.0 - prev[y.length].toDouble() / maxOf(x.length, y.length)
        }
    }
}
