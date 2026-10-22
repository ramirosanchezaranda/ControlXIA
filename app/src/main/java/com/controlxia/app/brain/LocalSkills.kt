package com.controlxia.app.brain

import java.text.Normalizer
import java.util.Locale

/**
 * Pedidos comunes que se entienden sin el modelo, con reglas (como Dicio):
 * mandar mensajes y audios, alarmas, temporizadores, la hora, buscar en una
 * app, abrir los reels, leer y responder mensajes, abrir un chat, guardar
 * apodos. Cada uno se traduce a la misma herramienta que pediría el modelo, así
 * las confirmaciones y la búsqueda de contactos son las mismas.
 *
 * Sirve para no gastar cupo en lo cotidiano y para seguir andando cuando el
 * proveedor no tiene cupo o no hay internet para el modelo.
 */
object LocalSkills {

    sealed interface Skill

    /** Una herramienta con sus argumentos, como la pediría el modelo. */
    data class Tool(val name: String, val args: Map<String, Any>) : Skill

    /** Leer los mensajes recibidos, en voz alta. */
    data class ReadMessages(val from: String? = null, val app: String? = null) : Skill

    /** Responder el último mensaje (de [from], si se dice). */
    data class ReplyLast(val text: String, val from: String? = null) : Skill

    /** Solo dijo el nombre (o algo que suena parecido, como "chevetó"). */
    data object JustTheName : Skill

    /** "¿Qué dijiste?": repetir la última respuesta. */
    data object Repeat : Skill

    /** Algo que se responde acá mismo ("cuánto es 25 por 4"). */
    data class Answer(val text: String) : Skill

    fun parse(text: String, agentName: String = "", today: java.time.LocalDate = java.time.LocalDate.now()): Skill? {
        if (isJustTheName(text, agentName)) return JustTheName
        Calculator.answer(text, agentName)?.let { return Answer(it) }
        val n = DirectCommands.normalize(text, agentName)
        if (n.isEmpty()) return null
        calendar(n, text, today)?.let { return it }
        RULES.forEach { (regex, build) ->
            regex.matchEntire(n)?.let { m -> build(m, text)?.let { return it } }
        }
        return null
    }

    /**
     * Si el pedido tiene más de una parte ("buscá zapatillas y mandale el link
     * a Juan"): las reglas solo entienden la primera, así que conviene que lo
     * resuelva el modelo. En un mensaje cuenta solo lo que sigue al texto
     * ("…que llego tarde y poneme una alarma"): el resto es el mensaje.
     */
    fun isCompound(text: String, skill: Skill): Boolean = when {
        // "Abrí Spotify y tocá Biblioteca" son dos pasos, pero esta habilidad hace los dos.
        skill is Tool && skill.name == "app_tap" ->
            Regex("\\b(?:y|despues|luego)\\b").containsMatchIn(plain(skill.args["label"]?.toString().orEmpty()))
        skill is Tool && skill.name in setOf("send_message", "send_audio") ->
            COMPOUND_TAIL.containsMatchIn(plain(skill.args["text"]?.toString().orEmpty()))
        skill is ReplyLast -> COMPOUND_TAIL.containsMatchIn(plain(skill.text))
        skill is Tool || skill is ReadMessages -> TaskCheck.isMultiAction(text)
        else -> false
    }

    private val COMPOUND_TAIL = Regex(
        "\\b(?:y|y despues|despues|luego|y tambien) (?:pone|poneme|abri|abrime|busca|buscame|manda|mandale|" +
            "llama|llamala|llamalo|escribi|escribile|toca|reproduci|activa|apaga|prende|leeme|lee|" +
            "decile|avisame|sacame|saca|graba|grabame|entra|anda)\\b"
    )

    // ---- Calendario: "agendá dentista el jueves a las 4 de la tarde" ----

    private val CALENDAR_VERB = Regex(
        "^(?:agenda|agendame|agendar|anota|anotame|anotar|recordame|recordar|haceme acordar(?: de)?|" +
            "crea un evento|creame un evento|pone en el calendario|anota en el calendario|agenda en el calendario)(?: que)? "
    )
    private val WEEKDAYS = listOf("lunes", "martes", "miercoles", "jueves", "viernes", "sabado", "domingo")
    private val MONTHS = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto",
        "septiembre", "octubre", "noviembre", "diciembre")
    private val DATE_WORDS = Regex(
        "\\b(?:para )?(?:(hoy|pasado manana|manana)|(?:el )?(${WEEKDAYS.joinToString("|")})(?: que viene)?|" +
            "el (\\d{1,2})(?: de (${MONTHS.joinToString("|")}))?)\\b"
    )
    private val AT_TIME = Regex("\\b(?:a las|a la|para las|tipo) $CLOCK\\b")

    private fun calendar(n: String, original: String, today: java.time.LocalDate): Skill? {
        val verb = CALENDAR_VERB.find(n) ?: return null
        var rest = n.substring(verb.range.last + 1)
        var date: java.time.LocalDate? = null
        DATE_WORDS.find(rest)?.let { m ->
            date = when {
                m.groups[1] != null -> when (m.groupValues[1]) {
                    "hoy" -> today
                    "manana" -> today.plusDays(1)
                    else -> today.plusDays(2)
                }
                m.groups[2] != null -> {
                    val target = WEEKDAYS.indexOf(m.groupValues[2]) + 1 // lunes = 1
                    var days = (target - today.dayOfWeek.value + 7) % 7
                    if (days == 0) days = 7
                    today.plusDays(days.toLong())
                }
                else -> {
                    val day = m.groupValues[3].toInt()
                    val month = m.groups[4]?.value?.let { MONTHS.indexOf(it) + 1 }
                    runCatching {
                        if (month != null) {
                            java.time.LocalDate.of(today.year, month, day).let { if (it < today) it.plusYears(1) else it }
                        } else {
                            java.time.LocalDate.of(today.year, today.monthValue, day).let { if (it < today) it.plusMonths(1) else it }
                        }
                    }.getOrNull()
                }
            }
            rest = rest.removeRange(m.range)
        }
        var time: Pair<Int, Int>? = null
        AT_TIME.find(rest)?.let { m ->
            time = clock(m, 1)
            rest = rest.removeRange(m.range)
        }
        if (date == null && time == null) return null
        val title = rest.replace(Regex("\\b(?:para|que|el|de)\\s*$"), "").replace(Regex("\\s+"), " ").trim()
            .removePrefix("de ").removePrefix("que ").trim()
        if (title.isEmpty()) return null
        val day = date ?: today.let { if (time != null && java.time.LocalTime.of(time!!.first, time!!.second) < java.time.LocalTime.now()) it.plusDays(1) else it }
        val (h, min) = time ?: (9 to 0)
        return Tool("calendar_event", mapOf(
            "title" to sentence(originalWords(original, title)),
            "date" to day.toString(),
            "time" to "%02d:%02d".format(h, min),
        ))
    }

    /** Las palabras de [phrase] (normalizada) tal como se dijeron, con tildes. */
    internal fun originalWords(original: String, phrase: String): String {
        val words = original.trim().split(Regex("\\s+"))
        val plainWords = words.map { plain(it) }
        val wanted = phrase.split(' ')
        for (i in 0..plainWords.size - wanted.size) {
            if (plainWords.subList(i, i + wanted.size) == wanted) {
                return words.subList(i, i + wanted.size).joinToString(" ").trim(',', '.', '¿', '?', '¡', '!')
            }
        }
        return phrase
    }

    // ---- Hora y fecha ----

    private val TIME = Regex("(?:que hora es|que hora tenes|que hora son|decime la hora|me decis la hora|hora)")
    private val DATE = Regex("(?:que (?:dia|fecha) es(?: hoy)?|que fecha (?:es|tenemos)(?: hoy)?|a que (?:dia )?estamos|decime la fecha|que dia es)")

    // ---- Alarmas y temporizadores ----

    private const val CLOCK = "(\\d{1,2})(?: (?:y )?(\\d{1,2}|media|cuarto)| menos (cuarto|\\d{1,2}))?(?: (?:de la |a la )?(manana|tarde|noche|madrugada)| (am|pm))?"

    /** "7 y media de la tarde" → 19:30. */
    private fun clock(m: MatchResult, from: Int): Pair<Int, Int>? {
        var hour = m.groups[from]?.value?.toIntOrNull() ?: return null
        var minute = when (val plus = m.groups[from + 1]?.value) {
            null -> 0
            "media" -> 30
            "cuarto" -> 15
            else -> plus.toIntOrNull() ?: return null
        }
        m.groups[from + 2]?.value?.let { minus ->
            hour -= 1
            minute = 60 - (if (minus == "cuarto") 15 else minus.toIntOrNull() ?: return null)
        }
        when (m.groups[from + 3]?.value ?: m.groups[from + 4]?.value) {
            "tarde", "pm" -> if (hour in 1..11) hour += 12
            "noche" -> if (hour in 6..11) hour += 12 else if (hour == 12) hour = 0
            "manana", "madrugada", "am" -> if (hour == 12) hour = 0
        }
        if (hour < 0) hour += 24
        return if (hour in 0..23 && minute in 0..59) hour to minute else null
    }

    private const val AMOUNT = "(\\d+|un|una|medio|media)"
    private const val UNIT = "(horas?|minutos?|min|segundos?|seg)"

    /** "5 minutos", "media hora", "1 hora y 10 minutos" → segundos. */
    private fun duration(text: String): Int? {
        val t = text.replace("media hora", "30 minutos").replace("un cuarto de hora", "15 minutos")
            .replace("cuarto de hora", "15 minutos").replace("hora y media", "hora y 30 minutos")
        var total = 0
        var found = false
        Regex("$AMOUNT $UNIT").findAll(t).forEach { m ->
            val amount = when (m.groupValues[1]) { "un", "una" -> 1.0; "medio", "media" -> 0.5; else -> m.groupValues[1].toDouble() }
            val unit = m.groupValues[2]
            total += when {
                unit.startsWith("hora") -> (amount * 3600).toInt()
                unit.startsWith("min") -> (amount * 60).toInt()
                else -> amount.toInt()
            }
            found = true
        }
        return total.takeIf { found && it > 0 }
    }

    // ---- Mensajes ----

    private const val SEND = "(?:mandale|mandales|enviale|envia|manda|escribile|escribirle|mandarle|enviarle)"
    private const val MSG = "(?: un| el)? (?:mensaje|mensajito|whatsapp|wasap|wsp|sms|texto)"
    private const val SAY = "(?:que|diciendo(?: que)?|diciendole(?: que)?|que diga|con el mensaje)"

    /** Lo que sigue al primer conector ("que", "diciendo") en el texto original, con sus mayúsculas y tildes. */
    private fun messageAfter(original: String, connectors: Set<String>, skipWords: Int = 0): String? {
        val words = original.trim().split(Regex("\\s+"))
        var index = -1
        for (i in skipWords until words.size) {
            if (plain(words[i]) in connectors) { index = i; break }
        }
        if (index < 0) return null
        // "que diga feliz cumple" → "feliz cumple".
        val rest = words.drop(index + 1).dropWhile { plain(it) in setOf("que", "diga", "dice", "diciendo") }
        return rest.joinToString(" ").trim().takeIf { it.isNotEmpty() }?.let(::sentence)
    }

    /** "llego tarde" → "Llego tarde". */
    internal fun sentence(text: String): String =
        text.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("es")) else it.toString() }

    private fun question(text: String): String {
        val body = text.trim().removePrefix("si ").removePrefix("Si ").trim().trimEnd('?', '.')
        return "¿${sentence(body)}?"
    }

    private fun via(n: String): String =
        Regex("\\bpor (telegram|messenger|instagram|signal)\\b").find(n)?.groupValues?.get(1)
            ?: if (Regex("\\b(?:sms|mensaje de texto)\\b").containsMatchIn(n)) "sms" else "whatsapp"

    /** "Llego tarde por Telegram" → el mensaje sin la app ("Llego tarde") y la app. */
    private fun message(tool: String, contact: String, text: String, n: String): Skill {
        val tail = Regex("\\s+(?:por|en|via) (telegram|messenger|instagram|signal|whatsapp|wasap|sms)[.!]?$", RegexOption.IGNORE_CASE).find(text)
        val app = tail?.groupValues?.get(1)?.lowercase()?.let { if (it == "wasap") "whatsapp" else it } ?: via(n)
        val body = tail?.let { text.removeRange(it.range).trim() } ?: text
        return Tool(tool, mapOf("contact" to contact, "text" to body, "app" to app))
    }

    private const val CHAT_APPS = "whatsapp|sms|telegram|messenger|instagram|signal"

    // ---- Búsquedas y secciones ----

    private val APPS = mapOf(
        "youtube" to "youtube", "mercado libre" to "mercadolibre", "mercadolibre" to "mercadolibre",
        "google" to "google", "spotify" to "spotify", "instagram" to "instagram", "tiktok" to "tiktok",
        "netflix" to "netflix", "play store" to "play_store", "la play store" to "play_store",
        "google maps" to "google_maps", "maps" to "google_maps", "el mapa" to "google_maps",
    )
    private val APP_NAMES = APPS.keys.sortedByDescending { it.length }.joinToString("|")

    /** El id de las apps con enlaces directos ("mercado libre" → "mercadolibre"); el resto, como se dijo. */
    internal fun appId(name: String): String = plain(name).let { APPS[it] ?: APPS[it.removePrefix("la app ")] ?: it }

    /** Si [app] tiene búsqueda con enlace directo (más rápida y confiable que tocar su lupa). */
    internal fun hasDeepSearch(app: String): Boolean = app in APPS.values

    /** Buscar [rawQuery] en [app] (con "más baratos" → ordenado por precio en Mercado Libre). */
    internal fun search(app: String, rawQuery: String): Skill? {
        var query = rawQuery.trim()
        var sort: String? = null
        Regex("\\b(?:(?:lo|la|el|los|las) )?mas barat[oa]s?\\b").find(query)?.let {
            sort = "price_low"; query = query.replace(it.value, " ")
        }
        Regex("\\b(?:(?:lo|la|el|los|las) )?mas car[oa]s?\\b").find(query)?.let {
            sort = "price_high"; query = query.replace(it.value, " ")
        }
        query = query.replace(Regex("^(?:(?:el|la|los|las|un|una|unas|unos|a) )"), "").replace(Regex("\\s+"), " ").trim()
        if (query.isEmpty()) return null
        val args = mutableMapOf<String, Any>("app" to app, "query" to query)
        if (sort != null && app == "mercadolibre") args["sort"] = sort!!
        return Tool("search_in_app", args)
    }

    private val SECTIONS = mapOf(
        "reels" to ("instagram" to "reels"), "historias" to ("instagram" to "home"),
        "mensajes directos" to ("instagram" to "inbox"), "directos" to ("instagram" to "inbox"),
        "explorar" to ("instagram" to "explore"), "shorts" to ("youtube" to "shorts"),
        "suscripciones" to ("youtube" to "subscriptions"), "mis compras" to ("mercadolibre" to "purchases"),
        "compras" to ("mercadolibre" to "purchases"),
    )
    private val SECTION_NAMES = SECTIONS.keys.sortedByDescending { it.length }.joinToString("|")

    private fun notifications(action: String, filter: String?): Skill {
        val args = mutableMapOf<String, Any>("action" to action)
        filter?.removePrefix("la ")?.removePrefix("el ")?.trim()?.takeIf { it.isNotEmpty() }?.let { args["filter"] = it }
        return Tool("notifications", args)
    }

    private val ALIASES = "pareja|mama|papa|jefe|jefa|novia|novio|esposa|esposo|mujer|marido|hermano|hermana|hijo|hija|abuela|abuelo|tia|tio|amigo|amiga|vieja|viejo"

    private val RULES: List<Pair<Regex, (MatchResult, String) -> Skill?>> = listOf(
        TIME to { _, _ -> Tool("get_time", emptyMap()) },
        DATE to { _, _ -> Tool("get_date", emptyMap()) },

        // Alarma: "poneme una alarma a las 7 y media", "despertame a las 8 de la mañana"
        Regex("(?:(?:pone|poneme|poner|crea|creame|programa|programame|activa|agenda) )?(?:una |la )?alarma (?:para |a )?(?:las |la )?$CLOCK(?: (?:para|que diga) .+)?") to { m, _ ->
            clock(m, 1)?.let { (h, min) -> Tool("set_alarm", mapOf("hour" to h, "minute" to min)) }
        },
        Regex("(?:despertame|levantame|despertarme) (?:a las |a la |mañana a las |manana a las )$CLOCK") to { m, _ ->
            clock(m, 1)?.let { (h, min) -> Tool("set_alarm", mapOf("hour" to h, "minute" to min)) }
        },

        // Temporizador: "poné un temporizador de 5 minutos", "avisame en media hora"
        Regex("(?:(?:pone|poneme|poner|crea|inicia|activa|arranca) )?(?:un |el )?(?:temporizador|timer|cronometro|cuenta regresiva) (?:de |por |a )?(.+)") to { m, _ ->
            duration(m.groupValues[1])?.let { Tool("set_timer", mapOf("seconds" to it)) }
        },
        Regex("(?:avisame|avisame en|recordame en|avisame dentro de) (?:en |dentro de )?(.+)") to { m, _ ->
            duration(m.groupValues[1])?.let { Tool("set_timer", mapOf("seconds" to it)) }
        },

        // Audios: "mandale un audio a Juan", "mandale un audio a Juan que diga feliz cumple"
        Regex("(?:$SEND|graba|grabale|manda|envia)(?:me)?(?: un| una)? (?:audio|nota de voz|mensaje de voz|audiito) (?:a |para )(.+?)(?: $SAY .+)?") to { m, original ->
            val text = messageAfter(original, setOf("que", "diciendo", "diciendole", "diga"))
            val args = mutableMapOf<String, Any>("contact" to m.groupValues[1])
            if (text != null) args["text"] = text
            Tool("send_audio", args)
        },

        // Mensajes: "mandale a Juan que llego tarde", "decile a mi pareja que la quiero"
        Regex("$SEND(?:$MSG)? (?:a |al |a la |para )(.+?) (?:(?:por|en) (?:$CHAT_APPS) )?$SAY (.+)") to { m, original ->
            val text = messageAfter(original, setOf("que", "diciendo", "diciendole"), skipWords = 2) ?: return@to null
            message("send_message", m.groupValues[1], text, m.value)
        },
        Regex("(?:decile|decirle|avisale|contale|deciles|avisales) a (.+?) (?:(?:por|en) (?:$CHAT_APPS) )?que (.+)") to { m, original ->
            val text = messageAfter(original, setOf("que"), skipWords = 2) ?: return@to null
            message("send_message", m.groupValues[1], text, m.value)
        },
        Regex("(?:preguntale|preguntales) (?:a |al )(.+?) (si|que|cuando|donde|como|a que hora) (.+)") to { m, original ->
            // La pregunta empieza después de "preguntale a <contacto>" en el texto original.
            val words = original.trim().split(Regex("\\s+"))
            val verb = words.indexOfFirst { plain(it).startsWith("preguntale") }.takeIf { it >= 0 } ?: return@to null
            val start = verb + 2 + m.groupValues[1].split(' ').size
            var body = words.drop(start)
            if (body.firstOrNull()?.let { plain(it) } == "si") body = body.drop(1)
            if (body.isEmpty()) return@to null
            Tool("send_message", mapOf("contact" to m.groupValues[1], "text" to question(body.joinToString(" ")), "app" to "whatsapp"))
        },

        // Responder: "respondele que sí", "contestale a Juan que en 10 llego"
        Regex("(?:respondele|contestale|respondeles|contestales|responde|contesta)(?: a (.+?))? (?:que |diciendo(?: que)? )(.+)") to { m, original ->
            val text = messageAfter(original, setOf("que", "diciendo")) ?: return@to null
            ReplyLast(text, m.groups[1]?.value)
        },

        // Notificaciones: "leeme las notificaciones", "qué notificaciones tengo", "borrá las notificaciones"
        Regex("(?:leeme|lee|leer|leelas|decime|que dicen|que me dicen|cuales son|repasame|contame)(?: las| mis)?(?: ultimas| nuevas)? notificaciones(?: nuevas| que tengo)?(?: (?:de|del|en) (.+))?") to { m, _ ->
            notifications("read", m.groups[1]?.value)
        },
        Regex("(?:que |cuantas )?notificaciones (?:tengo|hay|me llegaron|nuevas)(?: (?:de|del|en) (.+))?|(?:tengo|hay|me llegaron|llegaron) (?:alguna |algunas )?notificaci(?:on|ones)(?: nuevas?)?(?: (?:de|del) (.+))?") to { m, _ ->
            notifications("read", m.groups[1]?.value ?: m.groups[2]?.value)
        },
        Regex("(?:borra|borrame|borrar|limpia|limpiame|limpiar|descarta|descartar|elimina|eliminar|saca|sacame|cerra|quita) (?:todas )?(?:las |mis )?notificaciones") to { _, _ ->
            notifications("clear", null)
        },
        Regex("(?:abri|abrime|abrir|toca|entra a|entra en|anda a) (?:la )?(?:ultima )?notificacion(?: (?:de|del) (.+))?") to { m, _ ->
            notifications("open", m.groups[1]?.value)
        },

        // Leer mensajes
        Regex("(?:leeme|lee|leer|leeme los|decime|que dicen|que me dicen|cuales son)(?: los| mis| las)?(?: ultimos| nuevos)? (?:mensajes|whatsapps|wasaps|chats)(?: nuevos)?(?: de (.+?))?(?: (?:de|en|por) (whatsapp|telegram|instagram|sms|messenger))?") to { m, _ ->
            val from = m.groups[1]?.value?.takeIf { it !in setOf("whatsapp", "telegram", "instagram", "sms") }
            ReadMessages(from = from, app = m.groups[2]?.value ?: m.groups[1]?.value?.takeIf { it in setOf("whatsapp", "telegram", "instagram", "sms") })
        },
        Regex("(?:tengo|hay|me llegaron|llegaron) (?:mensajes|whatsapps|wasaps)(?: nuevos)?|que mensajes tengo|quien me escribio|me escribio alguien") to { _, _ -> ReadMessages() },

        // Buscar: "buscá zapatillas nike en mercado libre", "buscá en youtube recetas"
        Regex("(?:busca|buscame|buscar|busques|busca en internet|googlea|googlear) en ($APP_NAMES) (.+)") to { m, _ ->
            search(APPS.getValue(m.groupValues[1]), m.groupValues[2])
        },
        Regex("(?:busca|buscame|buscar|busques|googlea|googlear) (.+?) en ($APP_NAMES)") to { m, _ ->
            search(APPS.getValue(m.groupValues[2]), m.groupValues[1])
        },
        Regex("(?:pone|poneme|reproduci|pasame|quiero escuchar|quiero ver) (.+?) en (youtube|spotify)") to { m, _ ->
            search(APPS.getValue(m.groupValues[2]), m.groupValues[1])
        },
        // Cualquier otra app: "buscá pizza en PedidosYa" (con su buscador; si no existe, Google).
        Regex("(?:busca|buscame|buscar|busques) (.+) en (?:la app (?:de )?|la aplicacion (?:de )?)?([a-z0-9ñ]+(?: [a-z0-9ñ]+)?)") to { m, _ ->
            val query = m.groupValues[1].replace(Regex("^(?:a |al |el |la |los |las )"), "").trim()
            if (query.isEmpty() || Regex("\\b(?:chat|grupo|contacto|mensaje|archivo|foto)\\b").containsMatchIn(query)) null
            else Tool("app_search", mapOf("app" to m.groupValues[2], "query" to query))
        },
        Regex("(?:busca|buscame|buscar|googlea|googlear) (.+)") to { m, _ ->
            // Sin app: Google, salvo pedidos que son de otra cosa ("buscá el chat de…").
            if (Regex("\\b(?:chat|grupo|contacto|mensaje|archivo|foto)\\b").containsMatchIn(m.groupValues[1])) null
            else search("google", m.groupValues[1])
        },

        // Secciones de apps: "abrí los reels", "mis compras de mercado libre"
        Regex("(?:abri|abrime|mostrame|mostra|ver|anda a|entra a|quiero ver)(?: los| las| el| mis)? ($SECTION_NAMES)(?: (?:de|en) (?:instagram|youtube|mercado libre))?") to { m, _ ->
            SECTIONS[m.groupValues[1]]?.let { (app, section) -> Tool("open_app_section", mapOf("app" to app, "section" to section)) }
        },
        Regex("(?:llevame|como llego|navega|navegar|guiame) (?:a|hasta|al|a la) (.+)") to { m, _ ->
            Tool("open_app_section", mapOf("app" to "google_maps", "section" to "navigate", "value" to m.groupValues[1]))
        },

        // Tocar algo dentro de una app: "en Spotify tocá Biblioteca", "abrí Spotify y tocá Biblioteca"
        Regex("en ([a-z0-9ñ]+(?: [a-z0-9ñ]+)?) (?:toca|tocame|presiona|apreta|pulsa|selecciona|anda a|entra a|entra en) (?:el |la |los |las |en |a )?(.+)") to { m, original ->
            Tool("app_tap", mapOf("app" to m.groupValues[1], "label" to originalWords(original, m.groupValues[2])))
        },
        Regex("(?:abri|abrime|entra a|entra en|anda a) ([a-z0-9ñ]+(?: [a-z0-9ñ]+)?) y (?:toca|tocame|presiona|apreta|pulsa|selecciona|anda a|entra a|entra en) (?:el |la |los |las |en |a )?(.+)") to { m, original ->
            Tool("app_tap", mapOf("app" to m.groupValues[1], "label" to originalWords(original, m.groupValues[2])))
        },

        // Chats de WhatsApp: "abrí el chat de Juan", "abrí el grupo fútbol 2"
        Regex("(?:abri|abrime|abrir|entra a|entra en|anda a|mostrame) (?:el |la )?(?:chat|conversacion|grupo|whatsapp)(?: de| con)? (.+)") to { m, _ ->
            Tool("whatsapp_open_chat", mapOf("name" to m.groupValues[1]))
        },

        // Apodos: "mi pareja es Lucía"
        Regex("(?:guarda que |acordate que |anota que )?(?:mi |el |la )?($ALIASES) (?:es|se llama) (.+)") to { m, original ->
            val name = original.trim().split(Regex("\\s+")).let { words ->
                val idx = words.indexOfFirst { plain(it) in setOf("es", "llama") }
                if (idx < 0) null else words.drop(idx + 1).joinToString(" ")
            } ?: return@to null
            Tool("save_contact_alias", mapOf("alias" to m.groupValues[1], "contact" to name.trim().trimEnd('.')))
        },

        Regex("(?:repeti|repetime|repetilo|que dijiste|no te escuche|perdon que|como|otra vez)") to { _, _ -> Repeat },
    )

    /**
     * Solo el nombre del asistente, o lo que el reconocedor entendió en su lugar
     * ("che Beto" → "chevetó"): se compara cómo suena (b y v igual, sin tildes).
     */
    fun isJustTheName(text: String, agentName: String): Boolean {
        val name = sound(agentName)
        if (name.length < 2) return false
        var heard = sound(text).replace(" ", "")
        for (prefix in listOf("che", "oye", "hey", "ey", "hola", "dale", "bueno", "ok").map { sound(it) }) {
            if (heard.startsWith(prefix) && heard.length > prefix.length) heard = heard.removePrefix(prefix)
        }
        heard = heard.removeSuffix(sound("porfavor"))
        if (heard == name) return true
        if (heard.length > name.length + 2) return false
        return similarity(heard, name) >= 0.75
    }

    /** Cómo suena en rioplatense: sin tildes, b = v, s = z = c(e/i), y = ll, sin h (ch queda). */
    private fun sound(text: String): String =
        plain(text)
            .replace("ch", "x")
            .replace("v", "b").replace("z", "s").replace(Regex("c(?=[ei])"), "s")
            .replace("ll", "y").replace("h", "").replace("qu", "k").replace(Regex("c(?=[aou])"), "k")

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
    }

    private fun plain(text: String): String =
        Normalizer.normalize(text.lowercase(Locale("es")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9ñ ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
