package com.controlxia.app.iot

import java.text.Normalizer
import java.util.Locale

/**
 * Los pedidos para las teles y compus de la casa, sin el modelo: "prendé la
 * tele", "apagá la compu de escritorio", "subile 5 a la tele del living",
 * "prendé la tele y poné Netflix". Solo cuentan si nombran una tele o una
 * compu (o una vinculada por su nombre): "subí el volumen" a secas es del
 * teléfono. Sin Android: se prueba en la PC.
 */
object HomeCommands {

    enum class Action { ON, OFF, SLEEP, RESTART, LOCK, VOLUME_UP, VOLUME_DOWN, MUTE, PLAY, PAUSE, NEXT, PREVIOUS, HOME, BACK, OPEN }

    data class Command(
        val kind: HomeDevice.Kind,
        val action: Action,
        /** Cuál, si la nombraste ("living", "escritorio"); null = la única que hay. */
        val which: String? = null,
        /** La app o página a abrir. */
        val arg: String? = null,
        val times: Int = 1,
    )

    private val TV = Regex("""\b(tele|televisor|television|tv|smart ?tv)\b""")
    private val PC = Regex("""\b(compu|computadora|computador|pc|notebook|laptop|ordenador|maquina)\b""")
    private val SPLIT = Regex("""\s*(?:,|\by despues\b|\by luego\b|\bdespues\b|\bluego\b|\by\b)\s*""")
    private val WHICH = Regex("""\b(?:tele|televisor|television|tv|compu|computadora|computador|pc|notebook|laptop|maquina)\s+(?:del|de la|de mi|de|en el|en la)\s+(\w+(?:\s+\w+)?)""")
    private val TIMER = Regex("""\b(minutos?|horas?)\b""")

    private val ACTIONS: List<Pair<Action, Regex>> = listOf(
        Action.RESTART to Regex("""\breinici\w*"""),
        Action.LOCK to Regex("""\bbloque\w*"""),
        Action.SLEEP to Regex("""\b(suspend\w*|hibern\w*|a dormir|dormi\w*)"""),
        Action.MUTE to Regex("""\b(silenci\w*|mute\w*|sin sonido|(saca|quita)le el (sonido|volumen))"""),
        Action.VOLUME_UP to Regex("""\b(sub\w*|aument\w*|mas (volumen|fuerte))"""),
        Action.VOLUME_DOWN to Regex("""\b(baj\w*|dismin\w*|menos (volumen|fuerte)|mas bajo)"""),
        Action.PAUSE to Regex("""\b(pausa\w*|pone pausa|deten\w*|frena\w*)"""),
        Action.PLAY to Regex("""\b(play|reproduc\w*|segui\w*|continu\w*|despausa\w*|reanud\w*)"""),
        Action.NEXT to Regex("""\b(siguiente|proximo|adelanta\w*)"""),
        Action.PREVIOUS to Regex("""\banterior\b"""),
        Action.HOME to Regex("""\b(inicio|pantalla principal|menu principal|home)\b"""),
        Action.BACK to Regex("""\b(atras|volve)\b"""),
        Action.OFF to Regex("""\bapag\w*"""),
        Action.ON to Regex("""\b(prend\w*|encend\w*|enciend\w*|despert\w*)"""),
        Action.OPEN to Regex("""\b(pone\w*|pon|abri\w*|abrir|entra\w*|mira\w*|lanza\w*|ejecuta\w*)\s+(.+)"""),
    )

    private val NUMBERS = mapOf(
        "uno" to 1, "un" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6,
        "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10, "quince" to 15, "veinte" to 20,
    )

    /**
     * Los pedidos de [text], en orden, o null si no es para una tele o compu.
     * [tvNames] y [pcNames]: los nombres de las vinculadas.
     */
    fun parse(text: String, tvNames: List<String> = emptyList(), pcNames: List<String> = emptyList()): List<Command>? {
        val said = normalize(text)
        if (said.isEmpty() || TIMER.containsMatchIn(said)) return null
        val parts = SPLIT.split(said).map { it.trim() }.filter { it.isNotEmpty() }
        var kind: HomeDevice.Kind? = null
        var which: String? = null
        val commands = mutableListOf<Command>()
        for (part in parts) {
            val named = tvNames.firstOrNull { mentions(part, it) }?.let { HomeDevice.Kind.TV to it }
                ?: pcNames.firstOrNull { mentions(part, it) }?.let { HomeDevice.Kind.PC to it }
            val partKind = named?.first
                ?: when {
                    TV.containsMatchIn(part) -> HomeDevice.Kind.TV
                    PC.containsMatchIn(part) -> HomeDevice.Kind.PC
                    else -> null
                }
            if (partKind != null && partKind != kind) which = null
            kind = partKind ?: kind ?: return null
            which = named?.second ?: WHICH.find(part)?.groupValues?.get(1)?.trim() ?: which
            val (action, match) = ACTIONS.firstNotNullOfOrNull { (action, regex) -> regex.find(part)?.let { action to it } } ?: return null
            val final = when {
                kind == HomeDevice.Kind.TV && action == Action.SLEEP -> Action.OFF
                kind == HomeDevice.Kind.TV && (action == Action.LOCK || action == Action.RESTART) -> return null
                else -> action
            }
            val arg = if (final == Action.OPEN) cleanTarget(match.groupValues[2]).ifEmpty { return null } else null
            commands += Command(kind, final, which, arg, times(part, final))
        }
        return commands.takeIf { it.isNotEmpty() }
    }

    /** "netflix en la tele del living" → "netflix". */
    private fun cleanTarget(text: String): String = text
        .replace(Regex("""\b(en|a|de)\s+(la|el|mi)?\s*(tele|televisor|television|tv|compu|computadora|pc|notebook|laptop)\b.*$"""), "")
        .replace(Regex("""^(la app de|la aplicacion de|el|la|los|las|un|una)\s+"""), "")
        .trim()

    private fun times(part: String, action: Action): Int {
        if (action != Action.VOLUME_UP && action != Action.VOLUME_DOWN) return 1
        val digits = Regex("""\b(\d{1,2})\b""").find(part)?.groupValues?.get(1)?.toIntOrNull()
        val words = part.split(' ').firstNotNullOfOrNull { NUMBERS[it] }
        return (digits ?: words ?: DEFAULT_STEPS).coerceIn(1, 30)
    }

    private fun mentions(part: String, name: String): Boolean {
        val n = normalize(name)
        return n.isNotEmpty() && Regex("""\b${Regex.escape(n)}\b""").containsMatchIn(part)
    }

    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale("es")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9ñ ,]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Cuántos toques de volumen si no dijiste cuántos. */
    private const val DEFAULT_STEPS = 3
}

/** A dónde va cada app o página que se pide abrir ("poné Netflix en la tele", "abrí YouTube en la compu"). */
object HomeLinks {

    /** Apps de la tele: el enlace que la abre, y cómo se llama. */
    private val TV_APPS = listOf(
        Triple(listOf("netflix"), "https://www.netflix.com/title", "Netflix"),
        Triple(listOf("youtube", "you tube"), "https://www.youtube.com", "YouTube"),
        Triple(listOf("disney", "disney plus", "disney+"), "https://www.disneyplus.com", "Disney+"),
        Triple(listOf("prime", "prime video", "amazon", "amazon prime"), "https://app.primevideo.com", "Prime Video"),
        Triple(listOf("max", "hbo", "hbo max"), "https://play.max.com", "Max"),
        Triple(listOf("spotify"), "spotify://", "Spotify"),
        Triple(listOf("paramount", "paramount plus"), "https://www.paramountplus.com", "Paramount+"),
        Triple(listOf("apple tv", "apple"), "https://tv.apple.com", "Apple TV"),
        Triple(listOf("crunchyroll"), "https://www.crunchyroll.com", "Crunchyroll"),
        Triple(listOf("plex"), "plex://", "Plex"),
        Triple(listOf("twitch"), "twitch://", "Twitch"),
    )

    private val SITES = mapOf(
        "youtube" to "https://www.youtube.com",
        "netflix" to "https://www.netflix.com",
        "gmail" to "https://mail.google.com",
        "mail" to "https://mail.google.com",
        "correo" to "https://mail.google.com",
        "whatsapp" to "https://web.whatsapp.com",
        "spotify" to "https://open.spotify.com",
        "google" to "https://www.google.com",
        "facebook" to "https://www.facebook.com",
        "instagram" to "https://www.instagram.com",
        "twitter" to "https://x.com",
        "mercado libre" to "https://www.mercadolibre.com.ar",
        "disney" to "https://www.disneyplus.com",
        "prime video" to "https://www.primevideo.com",
        "max" to "https://play.max.com",
        "twitch" to "https://www.twitch.tv",
    )

    /** El enlace y el nombre de la app de la tele, o null si no la conozco. */
    fun tvApp(name: String): Pair<String, String>? {
        val said = HomeCommands.normalize(name)
        return TV_APPS.firstOrNull { (names, _, _) -> names.any { said == it || said.startsWith("$it ") } }
            ?.let { (_, link, label) -> link to label }
    }

    val tvAppNames: List<String> get() = TV_APPS.map { it.third }

    /** La página para la compu: las conocidas, una dirección dicha ("lanacion.com.ar") o una búsqueda en Google. */
    fun site(name: String): String {
        val said = HomeCommands.normalize(name)
        SITES[said]?.let { return it }
        val compact = said.replace(" punto ", ".").replace(" ", "")
        if (Regex("""^[a-z0-9-]+(\.[a-z0-9-]+)+(/.*)?$""").matches(compact)) return "https://$compact"
        return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(name.trim(), "UTF-8")
    }
}
