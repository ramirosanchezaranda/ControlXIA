package com.controlxia.app.brain

import kotlinx.serialization.json.JsonElement
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

/** Un pedido de herramienta del modelo. [id] lo asigna el proveedor (o se genera). */
data class ToolCallRequest(
    val id: String,
    val name: String,
    val arguments: JsonObject,
)

/** Lo que devolvió una herramienta, para mandárselo al modelo. */
data class ToolResult(
    val callId: String,
    val name: String,
    val content: String,
)

sealed interface LlmResponse {
    /** El modelo respondió con texto para decirle al usuario. */
    data class Text(val text: String) : LlmResponse

    /**
     * El modelo pidió ejecutar una o más herramientas. [raw] es el turno del
     * asistente tal como lo devolvió el proveedor (con razonamiento y firmas):
     * se le reenvía sin cambios en el paso siguiente, como exigen las APIs.
     */
    data class ToolCalls(val calls: List<ToolCallRequest>, val raw: JsonElement) : LlmResponse
}

/** Un paso del agente: lo que pidió el modelo y lo que devolvieron las herramientas. */
data class AgentStep(
    val request: LlmResponse.ToolCalls,
    val results: List<ToolResult>,
)

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
