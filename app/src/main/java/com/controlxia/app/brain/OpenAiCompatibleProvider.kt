package com.controlxia.app.brain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Cliente de la API Chat Completions con function calling. Sirve para OpenAI
 * y para cualquier servicio compatible (Groq, OpenRouter, Mistral, Ollama en
 * red local, etc.) cambiando la [baseUrl].
 *
 * Los compatibles no siempre respetan el formato al pie de la letra (content
 * null, arguments como objeto en vez de string, etc.), así que el parseo es
 * tolerante.
 */
class OpenAiCompatibleProvider(
    private val apiKey: String,
    private val model: String,
    baseUrl: String,
) : LlmProvider {

    private val url = baseUrl.trim().trimEnd('/') + "/chat/completions"

    override suspend fun complete(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
    ): LlmResponse {
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                messages.forEach { msg ->
                    add(buildJsonObject {
                        put("role", when (msg.role) {
                            ChatRole.SYSTEM -> "system"
                            ChatRole.USER -> "user"
                            ChatRole.ASSISTANT -> "assistant"
                        })
                        put("content", msg.content)
                    })
                }
            })
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.parameters)
                            })
                        })
                    }
                })
            }
        }

        val headers = buildMap {
            // Ollama local no requiere key; solo mandamos el header si hay una.
            if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
        }

        val response = HttpJson.post(url, headers, body)

        val message = ((response["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.get("message") as? JsonObject
            ?: throw LlmException(
                "Respuesta sin choices: ${response.toString().take(200)}",
                kind = LlmException.Kind.BAD_RESPONSE,
            )

        val toolCalls = message["tool_calls"] as? JsonArray
        val function = (toolCalls?.firstOrNull() as? JsonObject)?.get("function") as? JsonObject
        val name = (function?.get("name") as? JsonPrimitive)?.contentOrNull
        if (function != null && name != null) {
            return LlmResponse.ToolCall(name = name, arguments = parseArguments(function["arguments"]))
        }

        // OpenAI informa los rechazos en un campo aparte.
        (message["refusal"] as? JsonPrimitive)?.contentOrNull?.let { refusal ->
            if (refusal.isNotBlank()) return LlmResponse.Text(refusal)
        }
        return LlmResponse.Text(textContent(message["content"]))
    }

    /** "arguments" suele ser un string con JSON; algunos compatibles mandan el objeto. */
    private fun parseArguments(raw: JsonElement?): JsonObject = when (raw) {
        is JsonObject -> raw
        is JsonPrimitive -> raw.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { HttpJson.json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: JsonObject(emptyMap())
        else -> JsonObject(emptyMap())
    }

    /** content puede venir null, como string o como lista de partes. */
    private fun textContent(raw: JsonElement?): String = when (raw) {
        is JsonPrimitive -> raw.contentOrNull.orEmpty()
        is JsonArray -> raw.joinToString("") { part ->
            ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
        }
        else -> ""
    }
}
