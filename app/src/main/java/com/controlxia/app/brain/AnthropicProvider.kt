package com.controlxia.app.brain

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Cliente de la Messages API de Anthropic con tool use nativo.
 * https://docs.anthropic.com/en/api/messages
 */
class AnthropicProvider(
    private val apiKey: String,
    private val model: String,
) : LlmProvider {

    override suspend fun complete(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
    ): LlmResponse {
        // Anthropic recibe el system prompt por fuera de la lista de mensajes.
        val system = messages.filter { it.role == ChatRole.SYSTEM }
            .joinToString("\n") { it.content }

        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 1024)
            if (system.isNotBlank()) put("system", system)
            put("messages", buildJsonArray {
                messages.filter { it.role != ChatRole.SYSTEM }.forEach { msg ->
                    add(buildJsonObject {
                        put("role", if (msg.role == ChatRole.USER) "user" else "assistant")
                        put("content", msg.content)
                    })
                }
            })
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", tool.parameters)
                        })
                    }
                })
            }
        }

        val response = HttpJson.post(
            url = "https://api.anthropic.com/v1/messages",
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
            ),
            body = body,
        )

        val content = response["content"]?.jsonArray
            ?: throw LlmException("Respuesta sin contenido: $response")

        // Si el modelo pidió una tool, eso manda; si no, juntamos el texto.
        for (block in content) {
            val obj = block.jsonObject
            if (obj["type"]?.jsonPrimitive?.content == "tool_use") {
                return LlmResponse.ToolCall(
                    name = obj["name"]!!.jsonPrimitive.content,
                    arguments = obj["input"]?.jsonObject ?: buildJsonObject {},
                )
            }
        }
        val text = content
            .map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "text" }
            .joinToString("") { it["text"]?.jsonPrimitive?.content.orEmpty() }
        return LlmResponse.Text(text)
    }
}
