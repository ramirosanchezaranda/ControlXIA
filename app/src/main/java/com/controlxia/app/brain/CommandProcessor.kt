package com.controlxia.app.brain

import android.content.Context
import com.controlxia.app.voice.WakeWordSettings
import kotlinx.coroutines.CancellationException
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

    /** Detalle técnico del último error (para mostrar en la app), o null. */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * Procesa un comando ya transcripto y devuelve la respuesta hablada.
     * Nunca lanza: los errores se traducen a una frase para el usuario.
     */
    suspend fun process(command: String): String {
        val config = settings.activeConfig()
        if (config == null) {
            lastError = "Falta configurar el proveedor, la API key o el modelo"
            return "Necesito que configures el cerebro en la app para poder entenderte."
        }

        // El pedido entra a la memoria recién cuando hay respuesta: si la
        // llamada falla, no queda un turno suelto que rompa el próximo pedido.
        val messages = buildList {
            add(ChatMessage(ChatRole.SYSTEM, systemPrompt()))
            addAll(memory.history())
            add(ChatMessage(ChatRole.USER, command))
        }

        val response = try {
            LlmProviderFactory.create(config).complete(messages, registry.specs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            return spokenError(e)
        }
        lastError = null

        val spoken = when (response) {
            is LlmResponse.ToolCall -> try {
                registry.execute(response.name, response.arguments)
            } catch (e: Exception) {
                lastError = "Falló la acción ${response.name}: ${e.message}"
                "No pude hacer eso."
            }
            is LlmResponse.Text -> response.text.trim()
                .ifBlank { "No supe qué responder. ¿Me lo repetís?" }
        }
        memory.addExchange(command, spoken)
        return spoken
    }

    /** Traduce el error a una frase corta y útil para decir en voz alta. */
    private fun spokenError(e: Exception): String {
        val llm = e as? LlmException
            ?: return "Tuve un problema para procesar eso. Probá de nuevo."
        val detail = llm.message.orEmpty().lowercase()
        return when {
            llm.kind == LlmException.Kind.TIMEOUT ->
                "El cerebro tardó demasiado en responder. Probá de nuevo."
            llm.kind == LlmException.Kind.NETWORK ->
                "No tengo conexión a internet."
            "credit" in detail || "quota" in detail || "billing" in detail ->
                "Tu cuenta del proveedor se quedó sin crédito."
            llm.httpCode == 401 || llm.httpCode == 403 || "api key" in detail ->
                "La API key del cerebro no es válida. Revisala en la app."
            llm.httpCode == 404 || ("model" in detail && "not found" in detail) ->
                "El modelo configurado no existe. Elegí otro en la app."
            llm.httpCode == 429 ->
                "El proveedor me está limitando. Esperá un momento y probá de nuevo."
            (llm.httpCode ?: 0) >= 500 ->
                "El servicio del cerebro está con problemas. Probá en un rato."
            llm.httpCode == 400 ->
                "El proveedor rechazó el pedido. Revisá el modelo en la app."
            else -> "Tuve un problema para procesar eso. Probá de nuevo."
        }
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE d 'de' MMMM, HH:mm", Locale("es")).format(Date())
        val name = WakeWordSettings(appContext).agentName
        return """
            Sos $name, un asistente de voz en un teléfono Android. El usuario te
            habla en español rioplatense y vos respondés para ser escuchado en
            voz alta: frases cortas, naturales, sin markdown ni emojis.

            Fecha y hora actual: $now.

            Cuando el usuario pide una acción del teléfono (abrir una app, poner
            una alarma o temporizador, saber la hora o la fecha), usá la
            herramienta correspondiente. Para todo lo demás, respondé vos mismo
            en una o dos frases.

            Todavía no podés mandar mensajes, leer notificaciones ni hacer
            llamadas: si te lo piden, decí en una frase que eso llega en una
            próxima versión. No inventes acciones que no tenés.
        """.trimIndent()
    }
}
