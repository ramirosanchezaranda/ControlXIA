package com.controlxia.app.brain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Cliente de la Messages API de Anthropic con tool use nativo.
 * https://docs.anthropic.com/en/api/messages
 *
 * Los modelos actuales piensan antes de responder (thinking adaptativo) y esos
 * tokens cuentan dentro de max_tokens; por eso el margen es amplio y se pide
 * effort "low", que alcanza para respuestas de voz cortas y baja la latencia.
 *
 * En el bucle de herramientas, cada turno del asistente se reenvía tal cual
 * llegó (incluidos los bloques de thinking) y el historial solo crece: editar
 * pasos anteriores invalida el razonamiento y la API lo rechaza.
 */
class AnthropicProvider(
    private val apiKey: String,
    private val model: String,
) : LlmProvider {

    override val canCompactSteps: Boolean get() = false

    override suspend fun complete(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        steps: List<AgentStep>,
    ): LlmResponse {
        // Anthropic recibe el system prompt por fuera de la lista de mensajes.
        val system = messages.filter { it.role == ChatRole.SYSTEM }
            .joinToString("\n") { it.content }
        val useFallbacks = supportsServerFallbacks(model)

        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 4096)
            if (system.isNotBlank()) put("system", system)
            if (supportsEffort(model)) {
                put("output_config", buildJsonObject { put("effort", "low") })
            }
            // Si el clasificador de seguridad rechaza un pedido, el servidor lo
            // reintenta en el modelo de respaldo recomendado en vez de devolver
            // el rechazo.
            if (useFallbacks) put("fallbacks", "default")
            put("messages", buildJsonArray {
                messages.filter { it.role != ChatRole.SYSTEM }.forEach { msg ->
                    add(buildJsonObject {
                        put("role", if (msg.role == ChatRole.USER) "user" else "assistant")
                        put("content", msg.content)
                    })
                }
                steps.forEach { step ->
                    add(buildJsonObject {
                        put("role", "assistant")
                        put("content", step.request.raw)
                    })
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            step.results.forEach { result ->
                                add(buildJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", result.callId)
                                    put("content", result.content)
                                })
                            }
                        })
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

        val headers = buildMap {
            put("x-api-key", apiKey)
            put("anthropic-version", "2023-06-01")
            if (useFallbacks) put("anthropic-beta", "server-side-fallback-2026-07-01")
        }

        val response = HttpJson.post(
            url = "https://api.anthropic.com/v1/messages",
            headers = headers,
            body = body,
        )

        val stopReason = (response["stop_reason"] as? JsonPrimitive)?.contentOrNull
        if (stopReason == "refusal") {
            return LlmResponse.Text("Prefiero no ayudar con eso.")
        }

        val content = response["content"] as? JsonArray
            ?: throw LlmException(
                "Respuesta sin contenido: ${response.toString().take(200)}",
                kind = LlmException.Kind.BAD_RESPONSE,
            )
        val blocks = content.mapNotNull { it as? JsonObject }

        // Si el modelo pidió herramientas, eso manda; si no, juntamos el texto.
        // Los bloques de thinking se ignoran acá pero viajan en [raw].
        val calls = blocks
            .filter { it.str("type") == "tool_use" }
            .mapNotNull { block ->
                ToolCallRequest(
                    id = block.str("id") ?: return@mapNotNull null,
                    name = block.str("name") ?: return@mapNotNull null,
                    arguments = block["input"] as? JsonObject ?: JsonObject(emptyMap()),
                )
            }
        if (calls.isNotEmpty()) return LlmResponse.ToolCalls(calls, raw = content)

        val text = blocks
            .filter { it.str("type") == "text" }
            .joinToString("") { it.str("text").orEmpty() }
        if (text.isBlank() && stopReason == "max_tokens") {
            return LlmResponse.Text("Se me cortó la respuesta. ¿Me lo pedís de nuevo?")
        }
        return LlmResponse.Text(text)
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        /** Modelos que aceptan output_config.effort (Haiku 4.5 y anteriores no). */
        fun supportsEffort(model: String): Boolean =
            listOf(
                "claude-opus-5", "claude-sonnet-5", "claude-haiku-5", "claude-fable-5",
                "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6", "claude-opus-4-5",
                "claude-sonnet-4-6",
            ).any { model.startsWith(it) }

        /**
         * Modelos con respaldo del lado del servidor ("fallbacks": "default").
         * Claude Haiku 5.5 no lo tiene.
         */
        fun supportsServerFallbacks(model: String): Boolean =
            listOf("claude-opus-5", "claude-sonnet-5-5", "claude-fable-5")
                .any { model.startsWith(it) }
    }
}
