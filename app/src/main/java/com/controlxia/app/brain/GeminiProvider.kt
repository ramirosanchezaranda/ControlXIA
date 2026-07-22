package com.controlxia.app.brain

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Cliente de la API generateContent de Google Gemini con function calling.
 * https://ai.google.dev/api/generate-content
 */
class GeminiProvider(
    private val apiKey: String,
    private val model: String,
) : LlmProvider {

    override suspend fun complete(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
    ): LlmResponse {
        val system = messages.filter { it.role == ChatRole.SYSTEM }
            .joinToString("\n") { it.content }

        val body = buildJsonObject {
            if (system.isNotBlank()) {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", system) })
                    })
                })
            }
            put("contents", buildJsonArray {
                messages.filter { it.role != ChatRole.SYSTEM }.forEach { msg ->
                    add(buildJsonObject {
                        put("role", if (msg.role == ChatRole.USER) "user" else "model")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", msg.content) })
                        })
                    })
                }
            })
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("functionDeclarations", buildJsonArray {
                            tools.forEach { tool ->
                                add(buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", tool.parameters)
                                })
                            }
                        })
                    })
                })
            }
        }

        val response = HttpJson.post(
            url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
            headers = mapOf("x-goog-api-key" to apiKey),
            body = body,
        )

        val parts = response["candidates"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?: throw LlmException("Respuesta sin candidates: $response")

        for (part in parts) {
            val functionCall = part.jsonObject["functionCall"]?.jsonObject
            if (functionCall != null) {
                return LlmResponse.ToolCall(
                    name = functionCall["name"]!!.jsonPrimitive.content,
                    arguments = functionCall["args"]?.jsonObject ?: buildJsonObject {},
                )
            }
        }
        val text = parts.joinToString("") {
            it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
        }
        return LlmResponse.Text(text)
    }
}
