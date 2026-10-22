package com.controlxia.app.brain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * El modelo como traductor, no como manos: convierte un pedido que la app no
 * entendió en frases simples que sí entiende ("abrí mercado libre", "buscá
 * zapatillas", "ordená de menor a mayor precio"…), o responde si era una
 * pregunta. Las frases las vuelve a leer [TaskPlanner] y las hace la app, con
 * sus confirmaciones: el modelo no toca nada. Así un modelo chico no puede
 * equivocarse de botón ni mandar algo por su cuenta.
 */
object LlmPlanner {

    sealed interface Result {
        data class Steps(val phrases: List<String>) : Result
        data class Answer(val text: String) : Result
        data class Cannot(val reason: String) : Result
    }

    fun systemPrompt(agentName: String, now: String): String = """
        Sos $agentName, asistente de voz en un Android. Ahora: $now. Respondé SOLO con JSON, sin texto alrededor.
        Si es una pregunta o charla: {"answer":"respuesta corta en español rioplatense, para escuchar"}.
        Si pide hacer cosas en el teléfono, reescribilo como pasos con estas frases y nada más:
        {"steps":["abrí mercado libre","buscá zapatillas","ordená de menor a mayor precio","sacá captura de los primeros 4","guardalo en una nota"]}
        Frases que entiende la app (cambiá lo que está entre <>):
        abrí <app> · buscá <algo> · buscá <algo> en <app> · ordená de menor a mayor precio · ordená de mayor a menor precio ·
        sacá una captura · sacá captura de los primeros <n> · guardalo en una nota · abrí el chat de <nombre> ·
        escribí <texto> · mandalo · decile que <texto> · mandale un audio · mandale un audio que diga <texto> ·
        mandale a <contacto> que <texto> · mandale a <contacto> por <telegram|sms|instagram> que <texto> ·
        llamá a <contacto> · leeme los mensajes · leeme las notificaciones · poné una alarma a las <hora> ·
        temporizador de <n> minutos · agendá <evento> el <día> a las <hora> · subí el volumen · bajá el volumen ·
        prendé la linterna · apagá el wifi · tocá <botón> · bajá · subí · volvé · inicio · sacá una foto ·
        abrí los reels · llevame a <lugar> · pausá · siguiente canción.
        Si no se puede con esas frases: {"cannot":"qué parte no se puede, en pocas palabras"}.
        Nunca agregues pasos que el usuario no pidió. Nunca compres ni pagues.
    """.trimIndent()

    /** Lee la respuesta del modelo. Si no vino JSON, se toma como respuesta hablada. */
    fun parse(raw: String): Result {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return Result.Answer(text)
        val json = runCatching { HttpJson.json.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject }
            .getOrNull() ?: return Result.Answer(text)
        (json["steps"] as? JsonArray)?.let { array ->
            val phrases = array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { p -> p.isNotEmpty() } }
            if (phrases.isNotEmpty()) return Result.Steps(phrases.take(MAX_STEPS))
        }
        (json["answer"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return Result.Answer(it.trim()) }
        (json["cannot"] as? JsonPrimitive)?.contentOrNull?.let { return Result.Cannot(it.trim()) }
        return Result.Cannot("no entendí el pedido")
    }

    /** Las frases en un solo pedido, para que cada paso siga al anterior (la app abierta, el chat). */
    fun join(phrases: List<String>): String = phrases.joinToString(", después ") { it.trim().trimEnd('.') }

    private const val MAX_STEPS = 12
}
