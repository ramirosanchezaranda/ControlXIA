package com.controlxia.app.brain

import java.text.Normalizer
import java.util.Locale

/**
 * Controla que el modelo haya hecho todo lo pedido. Los modelos chicos (como
 * gpt-oss-20b) a veces dicen "listo, te lo mandé" sin haber usado ninguna
 * herramienta, o hacen la primera parte de "abrí WhatsApp y mandale hola a
 * Juan" y cortan. Acá se detectan esos casos con reglas baratas, sin gastar
 * otra llamada al modelo cuando todo está bien.
 */
object TaskCheck {

    /** Herramientas para moverse por la pantalla: no son una acción pedida. */
    private val NAVIGATION = setOf("screen_read", "screen_scroll", "screen_back")

    /** Abren una app y además hacen algo adentro: "entrá en X y buscá Y" en un paso. */
    private val DOUBLE = setOf("search_in_app", "open_app_section", "whatsapp_open_chat")

    /** "Abrí", "entrá": se cumplen solos cuando otra herramienta abre la app. */
    private val OPEN_VERBS = Regex("\\b(abri|abrime|abrilo|abrir|entra|entrar|anda|andate|metete)\\b")

    /** Verbos de pedido, en imperativo rioplatense (ya sin tildes). */
    private val ACTION_VERBS = Regex(
        "\\b(busc\\w*|mand\\w*|escrib\\w*|envia\\w*|respond\\w*|contest\\w*|" +
            "lee|leeme|leelo|leela|leelos|leer|mira|mirame|fijate|toc\\w*|" +
            "pon|pone|poneme|ponele|ponelo|poner|program\\w*|avis\\w*|" +
            "deci|decile|decime|deciles|reproduc\\w*|pasa|pasame|pasale|" +
            "llama|llamala|llamalo|llamar|guarda|guardame|guardalo|agenda|agendame|" +
            "activa|desactiva|apaga|prende|compart\\w*)\\b"
    )

    /** "Mandale a Juan y después a Pedro", "y a": más de una cosa con un solo verbo. */
    private val THEN = Regex("\\b(despues|luego|tambien|ademas|y a)\\b")

    /** Primera persona en pasado: "mandé", "abrí", "puse"… lo que dice haber hecho. */
    private val CLAIM = Regex(
        "\\b(mande|envie|abri|puse|programe|busque|toque|escribi|respondi|configure|active|" +
            "agende|guarde|llame|cree|borre|elimine|reproduci|pause|desactive|entre)\\b"
    )

    /** La frase entera: "Listo, también puse la alarma" no es un OK, es una respuesta. */
    private val OK = Regex(
        "^(listo |ok )?(ok|okay|okey|listo|todo listo|todo hecho|todo ok|esta todo|esta todo hecho|" +
            "ya esta|ya esta todo|no falta nada|nada mas)$"
    )

    /** Cuántas cosas pidió el usuario, contando los verbos de pedido. */
    fun requestedActions(command: String): Int {
        val text = normalize(command)
        return OPEN_VERBS.findAll(text).count() + ACTION_VERBS.findAll(text).count()
    }

    /** Si el pedido tiene más de una parte: entonces no se corta en la primera. */
    fun isMultiAction(command: String): Boolean =
        requestedActions(command) >= 2 || THEN.containsMatchIn(normalize(command))

    /**
     * Cuántas de las cosas pedidas se hicieron, según las herramientas usadas.
     * Una herramienta que abre la app y busca cubre también el "entrá" del pedido.
     */
    fun actionsDone(command: String, toolNames: List<String>): Int {
        var opens = OPEN_VERBS.findAll(normalize(command)).count()
        var done = 0
        for (name in toolNames) {
            when {
                name in NAVIGATION -> Unit
                name in DOUBLE && opens > 0 -> { opens--; done += 2 }
                else -> done++
            }
        }
        return done
    }

    /**
     * Por qué conviene revisar la respuesta final antes de decirla, o null si
     * no hace falta. [toolNames]: las herramientas que se usaron en el pedido.
     */
    fun reviewReason(command: String, answer: String, toolNames: List<String>): String? {
        val text = normalize(answer)
        val question = answer.trim().endsWith("?")
        return when {
            !question && toolNames.isEmpty() && CLAIM.containsMatchIn(text) ->
                "dice que hizo algo sin haber usado herramientas"
            !question && requestedActions(command) >= 2 &&
                actionsDone(command, toolNames) < requestedActions(command) ->
                "pidió ${requestedActions(command)} cosas y se hicieron ${actionsDone(command, toolNames)}"
            else -> null
        }
    }

    /** El modelo confirmó que no falta nada ("OK", "listo, está todo"). */
    fun isOk(answer: String): Boolean = OK.matches(normalize(answer))

    /**
     * El pedido de revisión: lo que pidió el usuario, lo que se hizo de verdad y
     * lo que el modelo iba a responder. [done] viene del registro de
     * herramientas, no de lo que dice el modelo.
     */
    fun reviewRequest(command: String, done: List<String>, draft: String): String = buildString {
        append("Control antes de responder. Mi pedido: «").append(command).append("».\n")
        append("Acciones hechas de verdad con herramientas: ")
        append(if (done.isEmpty()) "ninguna" else done.joinToString("; "))
        append(".\nIbas a responder: «").append(draft).append("».\n")
        append("Si falta hacer algo del pedido, hacelo ahora con las herramientas y al final ")
        append("resumí todo lo hecho en una frase. ")
        append("Si ya está todo hecho, respondé solo: OK")
    }

    /** Para seguir con el resto del pedido después de que el usuario confirmó una acción. */
    fun continueRequest(command: String, done: List<String>): String = buildString {
        append("Ya confirmé y se hizo. Mi pedido era: «").append(command).append("».\n")
        append("Hecho hasta ahora: ").append(done.joinToString("; ")).append(".\n")
        append("Si falta algo del pedido, hacelo con las herramientas y al final contá solo lo nuevo. ")
        append("Si no falta nada, respondé solo: OK")
    }

    /** Una línea para el registro de lo hecho: "send_message(Juan) → Listo, enviado". */
    fun describe(name: String, args: String, result: String): String =
        "$name(${args.take(80)}) → ${result.lineSequence().firstOrNull().orEmpty().take(100)}"

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale("es")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9ñ ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
