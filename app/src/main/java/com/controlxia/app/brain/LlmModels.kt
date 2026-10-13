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

/**
 * Error de una llamada al LLM. [message] es técnico (se muestra en la app para
 * diagnosticar); [httpCode] y [kind] permiten decir algo entendible en voz alta.
 */
class LlmException(
    message: String,
    cause: Throwable? = null,
    val httpCode: Int? = null,
    val kind: Kind = Kind.API,
) : Exception(message, cause) {
    enum class Kind { API, NETWORK, TIMEOUT, BAD_RESPONSE }
}
