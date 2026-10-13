package com.controlxia.app.brain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
        steps: List<AgentStep>,
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
                // Pasos del agente: el turno del modelo tal cual llegó (con sus
                // firmas de razonamiento) y las respuestas de las funciones.
                steps.forEach { step ->
                    add(step.request.raw)
                    add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray {
                            step.results.forEach { result ->
                                add(buildJsonObject {
                                    put("functionResponse", buildJsonObject {
                                        if (!result.callId.startsWith(GENERATED_ID)) put("id", result.callId)
                                        put("name", result.name)
                                        put("response", buildJsonObject { put("result", result.content) })
                                    })
                                })
                            }
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
                                    // Gemini rechaza un objeto con "properties"
                                    // vacío: las tools sin argumentos (get_time,
                                    // get_date) van sin "parameters".
                                    if (hasProperties(tool.parameters)) {
                                        put("parameters", tool.parameters)
                                    }
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

        val candidate = (response["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        val parts = ((candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }

        if (parts.isNullOrEmpty()) {
            // Sin contenido: bloqueado por los filtros de seguridad o cortado.
            val blocked = (response["promptFeedback"] as? JsonObject)?.get("blockReason") != null
            val finish = (candidate?.get("finishReason") as? JsonPrimitive)?.contentOrNull
            if (blocked || finish == "SAFETY" || finish == "PROHIBITED_CONTENT") {
                return LlmResponse.Text("Prefiero no ayudar con eso.")
            }
            if (candidate != null) return LlmResponse.Text("")
            throw LlmException(
                "Respuesta sin candidates: ${response.toString().take(200)}",
                kind = LlmException.Kind.BAD_RESPONSE,
            )
        }

        val calls = parts.mapIndexedNotNull { index, part ->
            val functionCall = part["functionCall"] as? JsonObject ?: return@mapIndexedNotNull null
            val name = (functionCall["name"] as? JsonPrimitive)?.contentOrNull
                ?: return@mapIndexedNotNull null
            ToolCallRequest(
                id = (functionCall["id"] as? JsonPrimitive)?.contentOrNull ?: "$GENERATED_ID$index",
                name = name,
                arguments = functionCall["args"] as? JsonObject ?: JsonObject(emptyMap()),
            )
        }
        if (calls.isNotEmpty()) {
            val content = candidate?.get("content") as? JsonObject ?: JsonObject(emptyMap())
            // El turno se reenvía igual, con el rol que Gemini espera.
            val raw = if (content.containsKey("role")) content else JsonObject(content + ("role" to JsonPrimitive("model")))
            return LlmResponse.ToolCalls(calls, raw)
        }
        // Las partes de "pensamiento" (thought = true) no se leen en voz alta.
        val text = parts
            .filter { (it["thought"] as? JsonPrimitive)?.booleanOrNull != true }
            .joinToString("") { (it["text"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
        return LlmResponse.Text(text)
    }

    private companion object {
        /** Prefijo de ids que genera la app cuando Gemini no manda uno (no se reenvían). */
        const val GENERATED_ID = "xia_call_"
    }

    private fun hasProperties(schema: JsonObject): Boolean =
        (schema["properties"] as? JsonObject)?.isNotEmpty() == true
}
