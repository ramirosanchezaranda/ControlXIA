package com.controlxia.app.voice

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Una transcripción escuchada tras el wake word. */
@Serializable
data class RecentCommand(
    val at: Long,
    val text: String,
)

/**
 * Guarda las últimas transcripciones para mostrarlas en el dashboard. Todavía
 * no se ejecuta ninguna acción: por ahora es el registro visible de lo que Xia
 * entendió. Mantiene solo las [MAX] más recientes.
 */
class RecentCommandsStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("recent_commands", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun all(): List<RecentCommand> =
        prefs.getString(KEY, null)
            ?.let { raw -> runCatching { json.decodeFromString<List<RecentCommand>>(raw) }.getOrNull() }
            ?: emptyList()

    fun add(text: String) {
        val updated = (all() + RecentCommand(System.currentTimeMillis(), text))
            .takeLast(MAX)
        prefs.edit().putString(KEY, json.encodeToString(updated)).apply()
    }

    private companion object {
        const val KEY = "commands"
        const val MAX = 15
    }
}
