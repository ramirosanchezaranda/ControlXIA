package com.controlxia.app.feedback

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Un feedback del usuario (bug, idea, mejora) con la respuesta que dio el LLM
 * configurado al analizarlo. El historial queda local y sirve como insumo
 * para mejorar los prompts y priorizar el roadmap.
 */
@Serializable
data class FeedbackEntry(
    val id: Long,
    val createdAt: Long,
    val text: String,
    val reply: String? = null,
    val tag: String? = null,
)

class FeedbackStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("feedback_store", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun all(): List<FeedbackEntry> =
        prefs.getString(KEY, null)
            ?.let { raw -> runCatching { json.decodeFromString<List<FeedbackEntry>>(raw) }.getOrNull() }
            ?: emptyList()

    fun add(entry: FeedbackEntry) {
        val updated = all() + entry
        prefs.edit().putString(KEY, json.encodeToString(updated)).apply()
    }

    private companion object {
        const val KEY = "entries"
    }
}
