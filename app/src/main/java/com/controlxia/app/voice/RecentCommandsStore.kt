package com.controlxia.app.voice

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Una transcripción escuchada tras el wake word y la respuesta de Xia. */
@Serializable
data class RecentCommand(
    val at: Long,
    val text: String,
    val reply: String? = null,
)

/**
 * Guarda los últimos comandos (lo que Xia escuchó + lo que respondió/ejecutó)
 * para mostrarlos en el dashboard. Mantiene solo los [MAX] más recientes.
 */
class RecentCommandsStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("recent_commands", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun all(): List<RecentCommand> =
        prefs.getString(KEY, null)
            ?.let { raw -> runCatching { json.decodeFromString<List<RecentCommand>>(raw) }.getOrNull() }
            ?: emptyList()

    /** Registra un comando escuchado (sin respuesta todavía). */
    fun add(text: String) {
        val updated = (all() + RecentCommand(System.currentTimeMillis(), text)).takeLast(MAX)
        save(updated)
    }

    /** Completa la respuesta de Xia sobre el último comando registrado. */
    fun addReply(reply: String) {
        val current = all().toMutableList()
        val last = current.lastOrNull() ?: return
        current[current.lastIndex] = last.copy(reply = reply)
        save(current)
    }

    private fun save(list: List<RecentCommand>) {
        prefs.edit().putString(KEY, json.encodeToString(list)).apply()
    }

    private companion object {
        const val KEY = "commands"
        const val MAX = 15
    }
}
