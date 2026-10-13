package com.controlxia.app.brain

import java.text.Normalizer
import java.util.Locale

/**
 * Una acción que espera el "sí" del usuario (mandar un mensaje, tocar
 * "Enviar" o "Comprar"). La confirmación la resuelve el código, no el modelo:
 * así ningún texto de una pantalla o de un mensaje puede saltearla.
 */
class PendingAction(
    /** Lo que se le pregunta al usuario: "¿Le mando a Lucía: «llego en 10»?". */
    val question: String,
    /** Qué mostrar mientras se ejecuta: "Enviando…". */
    val progress: String,
    val execute: suspend () -> String,
) {
    private val createdAt = System.currentTimeMillis()

    fun isExpired(): Boolean = System.currentTimeMillis() - createdAt > TTL_MS

    private companion object {
        const val TTL_MS = 2 * 60 * 1000L
    }
}

/**
 * Frases que cierran la conversación ("gracias", "listo", "chau"). Solo
 * cuentan si son la frase entera: "gracias, ahora poné una alarma" es un pedido.
 */
object Goodbye {

    private val PHRASES = setOf(
        "gracias", "muchas gracias", "mil gracias", "listo", "listo gracias", "nada", "nada mas",
        "nada mas gracias", "no", "no gracias", "no nada", "chau", "chao", "adios", "chau gracias",
        "eso es todo", "eso era todo", "eso nomas", "ya esta", "basta", "esta bien",
        "esta bien gracias", "perfecto", "perfecto gracias", "genial gracias", "buenisimo",
        "buenisimo gracias", "ok gracias", "dale gracias", "listo nada mas", "nada mas por ahora",
    )

    /** Qué decir al despedirse, o null si [text] no es una despedida ("" = cerrar en silencio). */
    fun reply(text: String): String? {
        val normalized = Normalizer.normalize(text.lowercase(Locale("es")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z ]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        if (normalized !in PHRASES) return null
        return when {
            "gracias" in normalized -> "De nada."
            normalized.startsWith("ch") || normalized == "adios" -> "Chau."
            else -> ""
        }
    }
}

/**
 * Pedidos de frenar: "pará", "basta", "cancelá". Mientras trabaja o habla los
 * escucha el motor de voz ([SPOKEN]); en una respuesta transcripta, [isStop]
 * reconoce la frase entera (con el nombre del asistente adelante o no).
 */
object StopWords {

    /** Palabras sueltas que el motor de voz escucha mientras el asistente trabaja o habla. */
    val SPOKEN = listOf("pará", "basta", "frená", "cancelá", "stop", "callate", "silencio", "detenete")

    private val PHRASES = setOf(
        "para", "para para", "para ya", "para por favor", "para un poco", "para todo", "para che",
        "frena", "frena ahi", "frena frena", "basta", "ya basta", "basta ya", "basta basta",
        "stop", "cancela", "cancelar", "cancelalo", "cancela todo", "callate", "silencio",
        "detenete", "deja", "dejalo", "deja de hablar", "olvidalo", "no para", "no no para",
        "no sigas", "no hagas nada", "alto", "pare", "ya esta para",
    )

    fun isStop(text: String, agentName: String = ""): Boolean {
        val name = normalize(agentName)
        val words = normalize(text).split(' ').filter { it.isNotEmpty() && it != name }
        return words.joinToString(" ") in PHRASES
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale("es")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-zñ ]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
}

/** Interpreta la respuesta hablada a una pregunta de confirmación. */
object Confirmation {

    enum class Answer { YES, NO, OTHER }

    private val YES = setOf(
        "si", "sí", "dale", "ok", "okey", "okay", "listo", "claro", "perfecto", "bueno", "obvio",
        "confirmo", "confirmar", "confirmado", "mandalo", "mandala", "manda", "envialo", "enviala",
        "envia", "hacelo", "adelante", "correcto", "exacto", "afirmativo", "porfa", "yes",
    )
    private val NO = setOf(
        "no", "nop", "cancela", "cancelar", "cancelalo", "deja", "dejalo", "negativo", "frena",
        "espera",
    )

    fun classify(text: String): Answer {
        val words = Normalizer.normalize(text.lowercase(Locale("es")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-zñ ]"), " ")
            .split(" ")
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return Answer.OTHER
        // Manda la primera palabra ("sí, no hay drama" → sí); si no decide, el
        // arranque de la frase ("mejor no" → no).
        val head = words.take(3)
        return when {
            words.first() in NO -> Answer.NO
            words.first() in YES -> Answer.YES
            head.any { it in NO } -> Answer.NO
            head.any { it in YES } -> Answer.YES
            else -> Answer.OTHER
        }
    }
}
