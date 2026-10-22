package com.controlxia.app.brain

import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

/**
 * Pedidos de varios pasos resueltos por la app, sin el modelo:
 *
 * "Necesito que entres en Mercado Libre, busques zapatillas, filtres de menor
 * a mayor precio, saques captura de los primeros 4 y lo guardes en una nota"
 * → abrir Mercado Libre · buscar «zapatillas» ahí · ordenar por menor precio ·
 * capturar 4 productos · guardar una nota.
 *
 * Parte el pedido en frases ("y", "después", comas), pasa cada verbo a la
 * forma de orden ("busques" → "buscá") y entiende cada frase con las mismas
 * reglas de los comandos directos y las habilidades locales, más las de este
 * archivo, que dependen de lo anterior: "buscá" busca en la app que quedó
 * abierta, "escribí" escribe en el chat que se abrió. Una frase que no se
 * entiende sola se pega a la anterior ("mandale que llego tarde y que traiga
 * pan" es un solo mensaje). Sin Android: se prueba en la PC.
 */
object TaskPlanner {

    sealed interface Step {
        /** Un comando directo ("abrí Instagram", "bajá", "subí el volumen"). */
        data class Direct(val command: DirectCommands.Command) : Step

        /** Una habilidad local (alarma, mensaje a un contacto, búsqueda en una app nombrada…). */
        data class Skill(val skill: LocalSkills.Skill) : Step

        /** Buscar en la app que quedó adelante. */
        data class Search(val app: String, val query: String, val sort: String?) : Step

        /** Ordenar los resultados ("price_low" o "price_high"). */
        data class Sort(val order: String) : Step

        /** Sacar captura de la pantalla; con [items], de los primeros N resultados (y anotarlos). */
        data class Capture(val items: Int?) : Step

        /** Guardar lo capturado o encontrado en un archivo de notas. */
        data class SaveNote(val title: String?) : Step

        /** Abrir un chat (o grupo) de WhatsApp. */
        data class OpenChat(val name: String) : Step

        /** Escribir en el chat abierto, sin mandar. */
        data class Write(val text: String) : Step

        /** Mandar lo que quedó escrito en el chat (pide el "sí"). */
        data object SendWritten : Step

        /** Mandar [text] en el chat abierto (pide el "sí"). */
        data class SendHere(val text: String) : Step

        /** Mandar un audio en el chat abierto; con [text], lo dice la voz del asistente. */
        data class AudioHere(val text: String?) : Step
    }

    data class Plan(val steps: List<Step>, val unknown: List<String>) {
        /** Se entendió todo. */
        val complete: Boolean get() = unknown.isEmpty() && steps.isNotEmpty()
    }

    /** Lo que quedó adelante después de cada paso: la app y el chat. */
    private data class Context(val app: String? = null, val chat: String? = null)

    private class Part(val text: String, val step: Step, val before: Context, val after: Context)

    fun plan(text: String, agentName: String = "", today: LocalDate = LocalDate.now()): Plan {
        val pieces = split(stripLeadIn(text.trim(), agentName))
        val parts = mutableListOf<Part>()
        val unknown = mutableListOf<String>()
        var context = Context()
        for ((separator, raw) in pieces) {
            val fragment = imperative(stripLeadIn(raw, agentName))
            if (DirectCommands.normalize(fragment, agentName).isEmpty()) continue
            val parsed = parse(fragment, context, agentName, today)
            if (parsed != null) {
                parts += Part(fragment, parsed.first, context, parsed.second)
                context = parsed.second
                continue
            }
            // No se entiende sola: es la continuación de la frase anterior.
            val previous = parts.lastOrNull()
            val merged = previous?.let { "${it.text}$separator$raw" }
            val reparsed = merged?.let { parse(it, previous.before, agentName, today) }
            if (previous != null && reparsed != null) {
                parts[parts.lastIndex] = Part(merged, reparsed.first, previous.before, reparsed.second)
                context = reparsed.second
            } else {
                unknown += raw.trim()
            }
        }
        return Plan(parts.map { it.step }, unknown)
    }

    /** Si el pedido es una pregunta (para el modelo, que responde sin tocar nada). */
    fun isQuestion(text: String, agentName: String = ""): Boolean {
        if ('?' in text || '¿' in text) return true
        val n = DirectCommands.normalize(text, agentName)
        return QUESTION.containsMatchIn(n)
    }

    private val QUESTION = Regex(
        "^(?:que|como|cuando|donde|por que|porque|quien|quienes|cual|cuales|cuanto|cuanta|cuantos|cuantas|" +
            "contame|explicame|decime|sabes|me podes decir|me decis|es verdad|existe|hay)\\b"
    )

    // ---- Frases ----

    /** Lo que sobra al principio: "necesito que", "Beto,", "por favor", "podrías". */
    private fun stripLeadIn(text: String, agentName: String): String {
        var t = text.trim().trimStart(',', '.', ' ')
        val name = plain(agentName)
        repeat(4) {
            val words = t.split(Regex("\\s+"), limit = 2)
            val first = plain(words.first())
            if (first in FILLERS || (name.isNotEmpty() && first == name)) {
                t = words.getOrElse(1) { "" }.trimStart(',', ' ')
            }
        }
        LEAD_IN.find(plain(t))?.let { m ->
            val drop = m.value.trim().split(' ').size
            t = t.trim().split(Regex("\\s+")).drop(drop).joinToString(" ")
        }
        return t.trim()
    }

    private val FILLERS = setOf("che", "dale", "bueno", "ok", "okey", "ahora", "porfa", "y", "despues", "luego", "entonces")
    private val LEAD_IN = Regex(
        "^(?:(?:necesito|quiero|quisiera|me gustaria|te pido) que |(?:me )?(?:podes|podrias|puedes|podria) |por favor |porfa )",
    )

    /** Parte en frases por "y", "después", "luego", comas y puntos, recordando el separador. */
    private fun split(text: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        var last = 0
        var separator = ""
        for (m in SEPARATOR.findAll(text)) {
            result += separator to text.substring(last, m.range.first)
            separator = m.value
            last = m.range.last + 1
        }
        result += separator to text.substring(last)
        return result.filter { it.second.isNotBlank() }
    }

    private val SEPARATOR = Regex(
        "\\s*(?:[,;]|\\.(?=\\s)|\\s(?:y después|y despues|y luego|y entonces|después|despues|luego|entonces|y)(?=\\s))\\s*",
        RegexOption.IGNORE_CASE,
    )

    /**
     * El primer verbo en forma de orden: "busques zapatillas" → "busca
     * zapatillas", "lo guardes" → "guardalo", "le mandes un audio" → "mandale
     * un audio". El resto de la frase queda como estaba (tildes y mayúsculas
     * de los mensajes).
     */
    internal fun imperative(fragment: String): String {
        var words = fragment.trim().split(Regex("\\s+")).toMutableList()
        if (words.isEmpty()) return fragment
        // "que busques…" (después de "y"): el "que" sobra si sigue un verbo conocido.
        if (plain(words[0]) == "que" && words.size > 1 && verb(words[1]) != null) words = words.drop(1).toMutableList()
        val first = plain(words[0])
        if (first in CLITICS && words.size > 1) {
            verb(words[1])?.let { base ->
                return (listOf(base + first) + words.drop(2)).joinToString(" ")
            }
        }
        verb(words[0])?.let { base ->
            words[0] = base
            return words.joinToString(" ")
        }
        // "guardarlo", "mandarle", "buscarme": infinitivo con el pronombre pegado.
        Regex("^([a-z]+)r(lo|la|los|las|le|les|me)$").matchEntire(first)?.let { m ->
            VERBS[m.groupValues[1] + "r"]?.let { base ->
                words[0] = base + m.groupValues[2]
                return words.joinToString(" ")
            }
        }
        return fragment.trim()
    }

    private fun verb(word: String): String? = VERBS[plain(word)]

    private val CLITICS = setOf("lo", "la", "los", "las", "le", "les", "me")

    /** Formas que no son orden (subjuntivo, infinitivo, presente) → la orden con voseo, sin tilde. */
    private val VERBS: Map<String, String> = buildMap {
        fun add(imperative: String, vararg forms: String) = forms.forEach { put(it, imperative) }
        add("entra", "entres", "entrar", "entras", "entrés")
        add("abri", "abras", "abrir", "abris")
        add("busca", "busques", "buscar", "buscas")
        add("filtra", "filtres", "filtrar", "filtras")
        add("ordena", "ordenes", "ordenar", "ordenas")
        add("saca", "saques", "sacar", "sacas")
        add("toma", "tomes", "tomar")
        add("captura", "captures", "capturar")
        add("guarda", "guardes", "guardar", "guardas")
        add("anota", "anotes", "anotar")
        add("escribi", "escribas", "escribir", "escribis")
        add("manda", "mandes", "mandar", "mandas")
        add("envia", "envies", "enviar", "envias")
        add("graba", "grabes", "grabar")
        add("toca", "toques", "tocar")
        add("pone", "pongas", "poner")
        add("llama", "llames", "llamar")
        add("lee", "leas", "leer")
        add("anda", "vayas", "ir")
        add("baja", "bajes")
        add("subi", "subas")
        add("comparti", "compartas", "compartir")
        add("responde", "respondas", "responder")
        add("contesta", "contestes", "contestar")
        add("reproduci", "reproduzcas", "reproducir")
        add("apaga", "apagues", "apagar")
        add("prende", "prendas", "prender")
        add("activa", "actives", "activar")
        add("mostra", "muestres", "mostrar")
        add("cerra", "cierres", "cerrar")
        add("volve", "vuelvas", "volver")
        add("deci", "digas", "decir")
        add("elegi", "elijas", "elegir")
        add("selecciona", "selecciones", "seleccionar")
    }

    // ---- Entender cada frase ----

    private fun parse(fragment: String, context: Context, agentName: String, today: LocalDate): Pair<Step, Context>? {
        val n = DirectCommands.normalize(fragment, agentName)
        if (n.isEmpty()) return null

        capture(n)?.let { return it to context }
        sort(n)?.let { return it to context }
        note(n, fragment)?.let { return it to context }
        CHAT.matchEntire(n)?.let { m ->
            val name = LocalSkills.originalWords(fragment, m.groupValues[1]).trim()
            return Step.OpenChat(name) to context.copy(app = "whatsapp", chat = name)
        }
        if (context.chat != null) inChat(n, fragment)?.let { return it to context }
        if (context.app != null) searchHere(n, context.app)?.let { return it to context }

        DirectCommands.parse(fragment, agentName)?.let { command ->
            val next = if (command is DirectCommands.OpenApp) {
                Context(app = LocalSkills.appId(command.name))
            } else {
                context
            }
            return Step.Direct(command) to next
        }
        LocalSkills.parse(fragment, agentName, today)?.let { skill ->
            if (skill == LocalSkills.JustTheName) return null
            return Step.Skill(skill) to after(skill, context)
        }
        return null
    }

    /** Qué queda adelante después de una habilidad (para "buscá…" o "escribí…" siguientes). */
    private fun after(skill: LocalSkills.Skill, context: Context): Context {
        val tool = skill as? LocalSkills.Tool ?: return context
        val app = tool.args["app"]?.toString()
        return when (tool.name) {
            "whatsapp_open_chat" -> Context(app = "whatsapp", chat = tool.args["name"]?.toString())
            "search_in_app", "app_search", "open_app_section", "app_tap" -> Context(app = app?.let { LocalSkills.appId(it) })
            else -> context
        }
    }

    private val CAPTURE_WORD = Regex("\\b(?:captura|capturas|screenshot|screenshots|pantallazo|pantallazos)\\b")
    private val CAPTURE_VERB = Regex("^(?:saca|sacame|sacale|toma|tomame|hace|haceme|captura|capturame|guarda una captura)\\b")
    /** "los primeros 4", "4 productos", "los 4 más baratos". */
    private val FIRST_N = Regex(
        "\\bprimer(?:os|as)? (\\d{1,2})\\b|\\b(\\d{1,2}) (?:primer(?:os|as)?|productos|resultados|articulos|publicaciones|opciones|items|mas barat|mas car)"
    )

    private fun capture(n: String): Step.Capture? {
        if (!CAPTURE_WORD.containsMatchIn(n)) return null
        if (!CAPTURE_VERB.containsMatchIn(n) && !n.startsWith("captura")) return null
        val count = FIRST_N.find(n)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()?.coerceIn(1, 20)
        return Step.Capture(count)
    }

    private val SORT_VERB = Regex("^(?:filtra|filtralo|filtralos|filtrala|filtralas|ordena|ordenalo|ordenalos|ordenala|ordenalas|pone|ponelo|ponelos|acomoda|acomodalos|mostrame|mostra|de|por|del)\\b")
    private val LOW = Regex("\\b(?:menor a mayor|mas barat[oa]s?|menor precio|precio mas bajo|mas economic[oa]s?|precio de menor)\\b")
    private val HIGH = Regex("\\b(?:mayor a menor|mas car[oa]s?|mayor precio|precio mas alto|precio de mayor)\\b")

    private fun sort(n: String): Step.Sort? {
        if (!SORT_VERB.containsMatchIn(n)) return null
        return when {
            LOW.containsMatchIn(n) -> Step.Sort("price_low")
            HIGH.containsMatchIn(n) -> Step.Sort("price_high")
            else -> null
        }
    }

    private val NOTE_VERB = Regex("^(?:guarda|guardalo|guardala|guardalos|guardalas|guardame|anota|anotalo|anotala|anotalos|anotalas|anotame|crea|creame|hace|haceme|pasalo|pasalos)\\b(.*)$")
    private val NOTE_WORD = Regex("\\b(?:nota|notas|archivo|documento|bloc|txt|texto)\\b")
    private val NOTE_TITLE = Regex("\\b(?:que se llame|llamad[oa]|con el (?:titulo|nombre)|titulad[oa]) (.+)$")

    private fun note(n: String, fragment: String): Step.SaveNote? {
        val m = NOTE_VERB.matchEntire(n) ?: return null
        val rest = m.groupValues[1].trim()
        val bare = rest.isEmpty() || rest in setOf("todo", "eso", "esto", "los datos", "la lista")
        if (!bare && !NOTE_WORD.containsMatchIn(rest)) return null
        val title = NOTE_TITLE.find(n)?.groupValues?.get(1)?.let { LocalSkills.originalWords(fragment, it) }
        return Step.SaveNote(title)
    }

    private val CHAT = Regex("(?:abri|abrime|entra|anda|ir|busca|buscame|mostrame)(?: a| al| en| a la)? (?:el |la )?(?:chat|conversacion|grupo)(?: de| con| del)? (.+)")

    private val SEND_WRITTEN = Regex("(?:manda|mandalo|mandala|mandaselo|envia|envialo|enviala|envialo ya|mandalo ya|dale enviar|dale a enviar|toca enviar)(?: el mensaje| ya| nomas)?")
    private val AUDIO = Regex("(?:manda|mandale|mandales|envia|enviale|graba|grabale|deja|dejale)(?: un| el)? audio(?: (?:que diga|diciendo|que dice|con) (.+))?")
    private val WRITE = Regex("(?:escribi|escribile|escribiles|pone|ponele|tipea)(?: que| el mensaje| un mensaje(?: que diga)?)? (.+)")
    private val SAY_HERE = Regex("(?:manda|mandale|mandales|envia|enviale|deci|decile|deciles|responde|respondele|contesta|contestale)(?: que| el mensaje| un mensaje(?: que diga)?)? (.+)")
    private val TO_SOMEONE = Regex("^(?:a|al|a la) \\S+ (?:que|diciendo)\\b")

    private fun inChat(n: String, fragment: String): Step? {
        if (SEND_WRITTEN.matches(n)) return Step.SendWritten
        AUDIO.matchEntire(n)?.let { m ->
            val text = m.groupValues[1].takeIf { it.isNotBlank() }?.let { body(fragment, it) }
            return Step.AudioHere(text)
        }
        WRITE.matchEntire(n)?.let { m ->
            if (TO_SOMEONE.containsMatchIn(m.groupValues[1])) return null
            return Step.Write(body(fragment, m.groupValues[1]))
        }
        SAY_HERE.matchEntire(n)?.let { m ->
            if (TO_SOMEONE.containsMatchIn(m.groupValues[1])) return null
            return Step.SendHere(body(fragment, m.groupValues[1]))
        }
        return null
    }

    private val SEARCH_HERE = Regex("(?:busca|buscame|buscar|encontra|encontrame|fijate)(?: si hay)? (.+)")
    private val EXPLICIT_APP = Regex("\\ben (?:la app (?:de )?|la aplicacion (?:de )?)?[a-z0-9ñ]+(?: [a-z0-9ñ]+)?$")

    private fun searchHere(n: String, app: String): Step.Search? {
        val m = SEARCH_HERE.matchEntire(n) ?: return null
        // "buscá X en otra app" lo entienden las habilidades.
        if (EXPLICIT_APP.containsMatchIn(m.groupValues[1]) && !m.groupValues[1].endsWith("en $app")) return null
        if (Regex("\\b(?:chat|grupo|contacto)\\b").containsMatchIn(m.groupValues[1])) return null
        val tool = LocalSkills.search(app, m.groupValues[1]) as? LocalSkills.Tool ?: return null
        val query = tool.args["query"]?.toString() ?: return null
        var sort = tool.args["sort"]?.toString()
        // En apps sin orden por enlace, "las más baratas" queda para ordenar en la app.
        if (sort == null) {
            sort = when {
                LOW.containsMatchIn(n) -> "price_low"
                HIGH.containsMatchIn(n) -> "price_high"
                else -> null
            }
        }
        return Step.Search(app, query, sort)
    }

    /** El texto libre tal como se dijo (tildes y mayúsculas), con la primera en mayúscula. */
    private fun body(fragment: String, normalized: String): String {
        var text = LocalSkills.originalWords(fragment, normalized)
        // Si el mensaje termina la frase, se le devuelve el signo final ("¿cómo estás?").
        val end = fragment.trimEnd().lastOrNull()
        if (end == '?' || end == '!') {
            val head = fragment.trimEnd().dropLast(1).trimEnd()
            if (head.endsWith(text) && !text.endsWith(end)) text += end
        }
        return LocalSkills.sentence(text)
    }

    private fun plain(text: String): String =
        Normalizer.normalize(text.lowercase(Locale("es")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9ñ ]"), "")
            .trim()
}
