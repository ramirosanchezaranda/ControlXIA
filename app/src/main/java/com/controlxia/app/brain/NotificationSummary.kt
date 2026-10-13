package com.controlxia.app.brain

import java.text.Normalizer
import java.util.Locale

/**
 * Arma lo que se dice al leer las notificaciones: agrupadas por app, de la más
 * nueva a la más vieja, cortas y sin repetir. Sin Android, para poder probarlo.
 */
object NotificationSummary {

    /** Una notificación visible: la app, el título (quién o de qué) y el texto. */
    data class Entry(val app: String, val title: String, val text: String)

    /**
     * "Tenés 4 notificaciones. WhatsApp: Juan, hola; Ana, ¿venís?. Gmail:
     * Banco, tu resumen." Con [filter] solo las de esa app o de ese remitente.
     */
    fun speak(entries: List<Entry>, filter: String? = null, limit: Int = MAX_READ): String {
        val wanted = filter?.let(::plain)?.takeIf { it.isNotBlank() }
        val list = entries
            .filter { line(it).isNotBlank() }
            .filter { wanted == null || matches(it, wanted) }
            .distinctBy { it.app to line(it) }
        if (list.isEmpty()) {
            return if (filter.isNullOrBlank()) "No tenés notificaciones." else "No tenés notificaciones de $filter."
        }
        val shown = list.take(limit)
        val intro = if (list.size == 1) "Tenés una notificación." else "Tenés ${list.size} notificaciones."
        val byApp = shown.groupBy { it.app }.entries.joinToString(" ") { (app, items) ->
            "$app: " + items.joinToString("; ") { line(it) }.trimEnd('.') + "."
        }
        val more = list.size - shown.size
        val tail = when {
            more == 1 -> " Y una más."
            more > 1 -> " Y $more más."
            else -> ""
        }
        return "$intro $byApp$tail"
    }

    /** La primera (la más nueva) que coincide con [filter], o la más nueva si no hay filtro. */
    fun <T> pick(entries: List<Pair<Entry, T>>, filter: String?): Pair<Entry, T>? {
        val wanted = filter?.let(::plain)?.takeIf { it.isNotBlank() }
            ?: return entries.firstOrNull()
        return entries.firstOrNull { matches(it.first, wanted) }
    }

    private fun matches(entry: Entry, wanted: String): Boolean {
        val app = plain(entry.app)
        val title = plain(entry.title)
        return app.contains(wanted) || (app.length >= 3 && wanted.contains(app)) ||
            title.contains(wanted) || (title.length >= 3 && wanted.contains(title))
    }

    /** "Juan, hola" (sin repetir la app como título y sin textos larguísimos). */
    private fun line(entry: Entry): String {
        val title = entry.title.trim().takeUnless { plain(it) == plain(entry.app) }.orEmpty()
        val text = short(entry.text.trim())
        return when {
            title.isEmpty() -> text
            text.isEmpty() || plain(text) == plain(title) -> title
            else -> "$title, $text"
        }
    }

    private fun short(text: String): String {
        val oneLine = text.replace(Regex("\\s+"), " ")
        if (oneLine.length <= MAX_CHARS) return oneLine
        val cut = oneLine.take(MAX_CHARS)
        return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > MAX_CHARS / 2 } ?: cut.length) + "…"
    }

    private fun plain(text: String): String =
        Normalizer.normalize(text.lowercase(Locale("es")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9ñ ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    const val MAX_READ = 8
    private const val MAX_CHARS = 120
}
