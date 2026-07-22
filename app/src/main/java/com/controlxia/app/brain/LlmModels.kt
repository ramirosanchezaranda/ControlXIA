package com.controlxia.app.brain

import kotlinx.serialization.json.JsonObject

enum class ChatRole { SYSTEM, USER, ASSISTANT }

data class ChatMessage(
    val role: ChatRole,
    val content: String,
)

/**
 * Definición de una herramienta que el LLM puede invocar.
 * [parameters] es un JSON Schema estándar (el mismo formato lo aceptan
 * Anthropic, OpenAI y Gemini con envoltorios distintos).
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

sealed interface LlmResponse {
    /** El modelo respondió con texto para decirle al usuario. */
    data class Text(val text: String) : LlmResponse

    /** El modelo decidió ejecutar una herramienta. */
    data class ToolCall(val name: String, val arguments: JsonObject) : LlmResponse
}

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)
