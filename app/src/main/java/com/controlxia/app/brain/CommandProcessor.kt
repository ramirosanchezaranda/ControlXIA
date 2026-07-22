package com.controlxia.app.brain

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cerebro de la app: toma la transcripción del comando, la manda al LLM
 * configurado con el catálogo de tools, ejecuta la acción que el modelo elija y
 * devuelve la frase a decir en voz alta. Si el modelo no llama a ninguna tool,
 * su texto es la respuesta hablada.
 *
 * Mantiene memoria conversacional corta entre pedidos. Todavía sin acciones de
 * mensajería (SMS/WhatsApp): eso llega con más permisos en la fase siguiente.
 */
class CommandProcessor(context: Context) {

    private val appContext = context.applicationContext
    private val settings = LlmSettings(appContext)
    private val registry = ToolRegistry(appContext)
    private val memory = ConversationMemory()

    /**
     * Procesa un comando ya transcripto y devuelve la respuesta hablada.
     * Nunca lanza: los errores se traducen a una frase para el usuario.
     */
    suspend fun process(command: String): String {
        val config = settings.activeConfig()
            ?: return "Necesito que configures el cerebro para poder entenderte."

        memory.add(ChatRole.USER, command)

        val messages = buildList {
            add(ChatMessage(ChatRole.SYSTEM, systemPrompt()))
            addAll(memory.history())
        }

        return try {
            val provider = LlmProviderFactory.create(config)
            when (val response = provider.complete(messages, registry.specs)) {
                is LlmResponse.ToolCall -> {
                    val spoken = registry.execute(response.name, response.arguments)
                    memory.add(ChatRole.ASSISTANT, spoken)
                    spoken
                }
                is LlmResponse.Text -> {
                    val spoken = response.text.ifBlank { "Listo." }
                    memory.add(ChatRole.ASSISTANT, spoken)
                    spoken
                }
            }
        } catch (e: Exception) {
            "Tuve un problema para procesar eso: ${e.message}"
        }
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE d 'de' MMMM, HH:mm", Locale("es")).format(Date())
        return """
            Sos Xia, un asistente de voz en un teléfono Android. El usuario te
            habla en español rioplatense y vos respondés para ser escuchado en
            voz alta: frases cortas, naturales, sin markdown ni emojis.

            Fecha y hora actual: $now.

            Cuando el usuario pide una acción del teléfono (abrir una app, poner
            una alarma o temporizador, saber la hora o la fecha), usá la
            herramienta correspondiente. Para todo lo demás, respondé vos mismo
            en una o dos frases. No inventes acciones que no tenés.
        """.trimIndent()
    }
}
