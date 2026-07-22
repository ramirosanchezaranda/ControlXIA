package com.controlxia.app.brain

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Cliente de la API Chat Completions con function calling. Sirve para OpenAI
 * y para cualquier servicio compatible (Groq, OpenRouter, Mistral, Ollama en
 * red local, etc.) cambiando la [baseUrl].
 */
class OpenAiCompatibleProvider(
    private val apiKey: String,
    private val model: String,
    baseUrl: String,
) : LlmProvider {

    private val url = baseUrl.trimEnd('/') + "/chat/completions"

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

        val message = response["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject
            ?: throw LlmException("Respuesta sin choices: $response")

        val toolCalls = message["tool_calls"]?.jsonArray
        if (!toolCalls.isNullOrEmpty()) {
            val function = toolCalls.first().jsonObject["function"]!!.jsonObject
            val rawArgs = function["arguments"]?.jsonPrimitive?.content ?: "{}"
            return LlmResponse.ToolCall(
                name = function["name"]!!.jsonPrimitive.content,
                arguments = HttpJson.json.parseToJsonElement(rawArgs).jsonObject,
            )
        }
        return LlmResponse.Text(message["content"]?.jsonPrimitive?.content.orEmpty())
    }
}
