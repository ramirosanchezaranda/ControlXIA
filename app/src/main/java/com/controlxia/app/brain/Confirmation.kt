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
