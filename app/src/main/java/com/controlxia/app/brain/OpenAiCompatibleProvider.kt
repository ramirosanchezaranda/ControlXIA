package com.controlxia.app.brain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Cliente de la API Chat Completions con function calling. Sirve para OpenAI,
 * Groq y cualquier servicio compatible (OpenRouter, Mistral, Ollama en red
 * local, etc.) cambiando la [baseUrl].
 *
 * Los compatibles no siempre respetan el formato al pie de la letra (content
 * null, arguments como objeto en vez de string, ids faltantes), así que el
 * parseo es tolerante.
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
        steps: List<AgentStep>,
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
                steps.forEach { step ->
                    add(step.request.raw)
                    step.results.forEach { result ->
                        add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", result.callId)
                            put("content", result.content)
                        })
                    }
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

        val response = try {
            HttpJson.post(url, headers, body)
        } catch (e: LlmException) {
            // Groq devuelve 400 "tool_use_failed" cuando el modelo arma mal una
            // llamada a herramienta; suele salir bien al segundo intento.
            if (e.httpCode == 400 && e.message.orEmpty().contains("tool", ignoreCase = true)) {
                HttpJson.post(url, headers, body)
            } else {
                throw e
            }
        }

        val message = ((response["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.get("message") as? JsonObject
            ?: throw LlmException(
                "Respuesta sin choices: ${response.toString().take(200)}",
                kind = LlmException.Kind.BAD_RESPONSE,
            )

        val calls = (message["tool_calls"] as? JsonArray).orEmpty()
            .mapIndexedNotNull { index, item ->
                val call = item as? JsonObject ?: return@mapIndexedNotNull null
                val function = call["function"] as? JsonObject ?: return@mapIndexedNotNull null
                val name = (function["name"] as? JsonPrimitive)?.contentOrNull
                    ?: return@mapIndexedNotNull null
                ToolCallRequest(
                    id = (call["id"] as? JsonPrimitive)?.contentOrNull ?: "call_$index",
                    name = name,
                    arguments = parseArguments(function["arguments"]),
                )
            }
        if (calls.isNotEmpty()) {
            return LlmResponse.ToolCalls(calls, raw = assistantTurn(message, calls))
        }

        // OpenAI informa los rechazos en un campo aparte.
        (message["refusal"] as? JsonPrimitive)?.contentOrNull?.let { refusal ->
            if (refusal.isNotBlank()) return LlmResponse.Text(refusal)
        }
        return LlmResponse.Text(textContent(message["content"]))
    }

    /**
     * El turno del asistente para reenviar en el paso siguiente. Se arma con los
     * ids ya normalizados, así cada resultado coincide con su llamada aunque el
     * servidor no haya mandado ids.
     */
    private fun assistantTurn(message: JsonObject, calls: List<ToolCallRequest>): JsonObject =
        buildJsonObject {
            put("role", "assistant")
            val text = textContent(message["content"])
            if (text.isBlank()) put("content", JsonNull) else put("content", text)
            put("tool_calls", buildJsonArray {
                calls.forEach { call ->
                    add(buildJsonObject {
                        put("id", call.id)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", call.name)
                            put("arguments", call.arguments.toString())
                        })
                    })
                }
            })
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
