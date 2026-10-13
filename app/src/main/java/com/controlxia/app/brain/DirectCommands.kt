package com.controlxia.app.brain

import java.text.Normalizer
import java.util.Locale

/**
 * Comandos que se resuelven en el teléfono, sin el modelo: "atrás", "bajá",
 * "tocá 5", "subí el volumen", "sacá una foto", "prendé la linterna"… Como el
 * Acceso por voz de Google. Son al instante, no gastan tokens y andan aunque se
 * haya terminado el cupo del proveedor.
 *
 * Solo entra la frase entera ("subí el volumen", no "subí el volumen y mandale
 * a Juan…"): lo que no coincide va al modelo como siempre.
 */
object DirectCommands {

    sealed interface Command

    enum class GlobalAction { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, LOCK, SCREENSHOT, POWER_MENU, SPLIT_SCREEN }
    data class Global(val action: GlobalAction) : Command

    enum class Direction { DOWN, UP, LEFT, RIGHT }
    data class Scroll(val direction: Direction) : Command

    enum class VolumeChange { UP, DOWN, MUTE, UNMUTE, SET }
    data class Volume(val change: VolumeChange, val percent: Int = 0, val steps: Int = 2) : Command

    enum class MediaAction { PLAY, PAUSE, NEXT, PREVIOUS }
    data class Media(val action: MediaAction) : Command

    /** [on] null = cambiar de estado. */
    data class Flashlight(val on: Boolean?) : Command

    enum class Setting { WIFI, BLUETOOTH, AIRPLANE, MOBILE_DATA }
    data class Toggle(val setting: Setting, val on: Boolean) : Command

    /** [percent] null = subir o bajar un poco según [up]. */
    data class Brightness(val up: Boolean, val percent: Int? = null) : Command

    data class Numbers(val show: Boolean) : Command
    data class TapNumber(val number: Int, val long: Boolean = false) : Command
    data class TapLabel(val label: String, val long: Boolean = false) : Command
    data class Type(val text: String) : Command
    data object ClearText : Command
    data object Enter : Command

    enum class CameraAction { OPEN, OPEN_VIDEO, SELFIE, PHOTO, SELFIE_PHOTO, START_VIDEO, STOP_VIDEO, SWITCH }
    data class Camera(val action: CameraAction) : Command

    /** "Pará el video": si se está grabando termina la grabación; si no, pausa. */
    data object StopVideoOrPause : Command

    data object Help : Command

    /** "Llamá a mamá" / "llamá al 1155554444". */
    data class Call(val who: String) : Command
    data object AnswerCall : Command
    data object HangUp : Command

    /** "Abrí Instagram": si no hay una app con ese nombre, el pedido va al modelo. */
    data class OpenApp(val name: String) : Command

    /**
     * El comando directo que es [text], o null si es un pedido para el modelo.
     * [numbersVisible]: con los números en pantalla, un número suelto ("5") es
     * "tocá 5"; sin ellos puede ser la respuesta a una pregunta ("¿cuántos minutos?").
     */
    fun parse(text: String, agentName: String = "", numbersVisible: Boolean = false): Command? {
        val n = normalize(text, agentName)
        if (n.isEmpty()) return null
        RULES.forEach { (regex, build) ->
            regex.matchEntire(n)?.let { match -> build(match, text)?.let { return it } }
        }
        if (numbersVisible) {
            Regex("(?:el )?(?:numero )?(\\d{1,3})").matchEntire(n)?.let {
                return TapNumber(it.groupValues[1].toInt())
            }
        }
        return null
    }

    private val TYPE_VERBS = listOf("escribi", "escribir", "escribe", "escribime", "tipea", "tipear", "dicta", "dictar")

    private val INDIRECT = Regex("^(?:decirle|decile|deciles|le |les |a [a-z]+ que|un mensaje|una respuesta|en whatsapp|por whatsapp)")

    private val V_TOCA = "(?:toca|tocar|tocame|presiona|presionar|apreta|apretar|pulsa|selecciona|seleccionar|clic en|click en|clickea|cliquea|dale a|dale click a)"
    private val V_MANTENE = "(?:mantene|mantener|mantenes|deja|dejar|hace) (?:apretado|presionado|pulsado|una pulsacion larga en|click largo en)"
    private val V_PRENDE = "(?:prende|prender|prendeme|encende|encender|enciende|activa|activar|activame|pone|poner|conecta|conectar)"
    private val V_APAGA = "(?:apaga|apagar|apagame|desactiva|desactivar|desconecta|desconectar|saca|sacar|quita|quitar)"
    private val V_SUBI = "(?:subi|subir|subile|subime|sube|aumenta|aumentar|aumentale|mas|levanta|levantar)"
    private val V_BAJA = "(?:baja|bajar|bajale|bajame|disminui|disminuir|reduci|reducir|menos)"
    private val V_MOSTRA = "(?:mostra|mostrar|mostrame|abri|abrir|abrime|ver|pone|poner|baja|bajame|despliega|desplega)"

    private val SETTINGS = mapOf(
        "wifi" to Setting.WIFI, "wi fi" to Setting.WIFI, "wai fai" to Setting.WIFI, "internet" to Setting.WIFI,
        "bluetooth" to Setting.BLUETOOTH, "blutu" to Setting.BLUETOOTH, "blututh" to Setting.BLUETOOTH,
        "modo avion" to Setting.AIRPLANE, "datos" to Setting.MOBILE_DATA, "datos moviles" to Setting.MOBILE_DATA,
    )
    private val SETTING_NAMES = SETTINGS.keys.sortedByDescending { it.length }.joinToString("|")

    private fun level(word: String): Int? = when (word) {
        "maximo", "todo", "full", "tope" -> 100
        "minimo", "nada" -> 0
        "mitad", "medio", "la mitad" -> 50
        else -> word.toIntOrNull()?.coerceIn(0, 100)
    }

    private val RULES: List<Pair<Regex, (MatchResult, String) -> Command?>> = listOf(
        // Sistema
        rule("(?:atras|volve|volver|volve atras|anda atras|ir atras|para atras|hacia atras|regresa|regresar|retrocede|retroceder)") { Global(GlobalAction.BACK) },
        rule("(?:inicio|pantalla (?:de )?inicio|(?:anda|ir|volve|volver|llevame) al inicio|(?:anda|ir|volve) a la pantalla (?:de )?inicio|home|escritorio|menu principal)") { Global(GlobalAction.HOME) },
        rule("(?:$V_MOSTRA )?(?:las )?(?:apps|aplicaciones) recientes|recientes|multitarea|(?:$V_MOSTRA )?(?:las )?(?:apps|aplicaciones) abiertas") { Global(GlobalAction.RECENTS) },
        rule("(?:$V_MOSTRA )?(?:las )?notificaciones|(?:la )?barra de notificaciones") { Global(GlobalAction.NOTIFICATIONS) },
        rule("(?:$V_MOSTRA )?(?:la |los )?(?:configuracion|ajustes|menu) rapid[ao]s?") { Global(GlobalAction.QUICK_SETTINGS) },
        rule("(?:bloquea|bloquear|bloqueame)(?: el (?:telefono|celular|cel)| la pantalla)?") { Global(GlobalAction.LOCK) },
        rule("(?:(?:saca|sacar|sacame|toma|tomar|tomame|hace|hacer|haceme) (?:una )?)?(?:captura|captura de pantalla|screenshot|pantallazo)") { Global(GlobalAction.SCREENSHOT) },
        rule("(?:menu de apagado|(?:apaga|apagar) el (?:telefono|celular)|reinicia(?:r)? el (?:telefono|celular))") { Global(GlobalAction.POWER_MENU) },
        rule("(?:pantalla dividida|dividi la pantalla|dividir pantalla)") { Global(GlobalAction.SPLIT_SCREEN) },

        // Desplazar
        rule("(?:baja|bajar|bajale|segui bajando|scrollea|scroll|desplaza(?:te)?(?: hacia)? abajo|(?:mas|un poco mas) abajo|baja mas|baja un poco|anda (?:mas )?abajo)") { Scroll(Direction.DOWN) },
        rule("(?:subi|subir|subile|segui subiendo|desplaza(?:te)?(?: hacia)? arriba|(?:mas|un poco mas) arriba|subi mas|subi un poco|anda (?:mas )?arriba|volve arriba)") { Scroll(Direction.UP) },
        rule("(?:desliza|deslizar|pasa|pasar|move|mover|scrollea)(?: hacia)? (?:a )?(?:la )?(izquierda|derecha)") { m, _ ->
            Scroll(if (m.groupValues[1] == "izquierda") Direction.LEFT else Direction.RIGHT)
        },

        // Volumen
        rule("$V_SUBI (?:el |al )?(?:volumen|sonido)(?: (un poco|mas|bastante|mucho|a full))?") { m, _ ->
            when (m.groupValues[1]) {
                "a full" -> Volume(VolumeChange.SET, 100)
                "bastante", "mucho" -> Volume(VolumeChange.UP, steps = 4)
                "un poco" -> Volume(VolumeChange.UP, steps = 1)
                else -> Volume(VolumeChange.UP)
            }
        },
        rule("$V_BAJA (?:el |al )?(?:volumen|sonido)(?: (un poco|mas|bastante|mucho))?") { m, _ ->
            when (m.groupValues[1]) {
                "bastante", "mucho" -> Volume(VolumeChange.DOWN, steps = 4)
                "un poco" -> Volume(VolumeChange.DOWN, steps = 1)
                else -> Volume(VolumeChange.DOWN)
            }
        },
        rule("(?:pone el )?volumen (?:al |a |en )?(?:el |la )?(maximo|minimo|mitad|medio|tope|\\d{1,3})(?: por ciento)?|(?:subi|pone) el volumen al (maximo|tope)|a todo volumen") { m, _ ->
            val word = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: "maximo"
            level(word)?.let { Volume(VolumeChange.SET, it) }
        },
        rule("(?:silencia|silenciar|silencialo|mutea|mutear|sin sonido|quita(?:r)? el sonido|saca(?:r)? el sonido|pone(?:r)? en silencio)(?: el (?:telefono|celular|video|sonido))?") { Volume(VolumeChange.MUTE) },
        rule("(?:activa(?:r)?|devolve(?:r)?|pone(?:r)?) el sonido|saca(?:r)? el silencio|desmutea(?:r)?") { Volume(VolumeChange.UNMUTE) },

        // Música y videos
        rule("(?:pausa|pausar|pausalo|pausala|pone pausa|pone(?:r)? en pausa|pausa(?:r)? (?:el|la) (?:video|musica|cancion|tema|serie|pelicula))") { Media(MediaAction.PAUSE) },
        rule("(?:para|parar|frena|frenar|deten|detener) (?:el|la) (?:video|grabacion)") { StopVideoOrPause },
        rule("(?:para|parar|frena|frenar|deten|detener) (?:la) (?:musica|cancion|serie|pelicula)") { Media(MediaAction.PAUSE) },
        rule("(?:play|reproduci|reproducir|reproducilo|dale play|pone play|reanuda|reanudar|continua|continuar|segui reproduciendo)(?: (?:el|la) (?:video|musica|cancion|tema|serie|pelicula))?") { Media(MediaAction.PLAY) },
        rule("(?:(?:pasa|pasar|salta|saltar|pone|poner|anda a)(?: (?:la|el))? )?(?:siguiente|proxima|proximo) (?:cancion|tema|video|capitulo)|(?:cancion|tema|video|capitulo) siguiente|(?:pasa|salta|saltea)(?: (?:la|el|este|esta))? (?:cancion|tema|video)") { Media(MediaAction.NEXT) },
        rule("(?:(?:pone|volve a)(?: (?:la|el))? )?(?:cancion|tema|video) anterior|anterior (?:cancion|tema|video)") { Media(MediaAction.PREVIOUS) },

        // Linterna
        rule("$V_PRENDE (?:la )?linterna") { Flashlight(true) },
        rule("$V_APAGA (?:la )?linterna") { Flashlight(false) },
        rule("linterna") { Flashlight(null) },

        // Wi-Fi, Bluetooth, modo avión, datos
        rule("$V_PRENDE (?:el |la |los )?($SETTING_NAMES)") { m, _ -> SETTINGS[m.groupValues[1]]?.let { Toggle(it, true) } },
        rule("$V_APAGA (?:el |la |los )?($SETTING_NAMES)") { m, _ -> SETTINGS[m.groupValues[1]]?.let { Toggle(it, false) } },

        // Brillo
        rule("$V_SUBI (?:el )?brillo(?: de la pantalla)?") { Brightness(up = true) },
        rule("$V_BAJA (?:el )?brillo(?: de la pantalla)?") { Brightness(up = false) },
        rule("(?:pone el )?brillo (?:al |a |en )?(?:el |la )?(maximo|minimo|mitad|medio|tope|\\d{1,3})(?: por ciento)?") { m, _ ->
            level(m.groupValues[1])?.let { Brightness(up = it >= 50, percent = it) }
        },

        // Números en pantalla
        rule("(?:$V_MOSTRA|activa|activar) (?:los )?numeros|numeros|mostrar numeros") { Numbers(true) },
        rule("(?:oculta|ocultar|ocultalos|saca|sacar|sacalos|esconde|esconder|quita|quitar|borra|borrar) (?:los )?numeros") { Numbers(false) },
        rule("$V_MANTENE (?:el |en el )?(?:numero )?(\\d{1,3})") { m, _ -> TapNumber(m.groupValues[1].toInt(), long = true) },
        rule("$V_TOCA (?:el |en el )?(?:numero )?(\\d{1,3})") { m, _ -> TapNumber(m.groupValues[1].toInt()) },
        rule("$V_MANTENE (?:en )?(?:el |la |los |las )?(?:boton (?:de |del )?)?(.+)") { m, _ -> TapLabel(m.groupValues[1], long = true) },
        rule("$V_TOCA (?:en )?(?:el |la |los |las )?(?:boton (?:de |del )?|opcion )?(.+)") { m, _ -> TapLabel(m.groupValues[1]) },

        // Escribir
        rule("(?:${TYPE_VERBS.joinToString("|")})(?: que)? (.+)") { m, original ->
            // "Escribí decirle que…", "escribí a Juan que…": es un mensaje, va al modelo.
            if (INDIRECT.containsMatchIn(m.groupValues[1])) null else textAfterVerb(original)?.let { Type(it) }
        },
        rule("(?:borra|borrar|limpia|limpiar)(?: todo)?(?: el)? (?:texto|campo|lo escrito)|borra todo|borrar todo") { ClearText },
        rule("(?:enter|intro|apreta enter|dale enter|toca enter)") { Enter },

        // Cámara
        rule("(?:abri|abrir|abrime|anda a|pone) (?:la )?camara(?: de fotos)?|camara") { Camera(CameraAction.OPEN) },
        rule("(?:abri|abrir|abrime|pone) (?:la )?camara (?:de|para|en) video|(?:abri|pone) (?:el )?modo video|modo video") { Camera(CameraAction.OPEN_VIDEO) },
        rule("(?:abri|abrir|abrime|pone|cambia a) (?:la )?camara (?:frontal|delantera|de selfie|de adelante)|camara (?:frontal|delantera)|modo selfie") { Camera(CameraAction.SELFIE) },
        rule("(?:saca|sacar|sacame|sacate|toma|tomar|tomame|hace|hacer|haceme)(?:me)? (?:una )?(?:selfie|autofoto|foto (?:con la camara )?(?:frontal|delantera))|selfie") { Camera(CameraAction.SELFIE_PHOTO) },
        rule("(?:saca|sacar|sacame|toma|tomar|tomame|hace|hacer|haceme) (?:una |la )?foto|foto|dispara|disparar") { Camera(CameraAction.PHOTO) },
        rule("(?:empeza|empezar|empeza a|inicia|iniciar|comenza|comenzar|arranca|arrancar|activa)(?: a)? (?:grabar|filmar)(?: (?:un )?video)?|(?:empeza|empezar|inicia|iniciar|comenza|comenzar|arranca|arrancar) (?:un |el |la )?(?:video|grabacion|filmacion)|(?:graba|grabar|grabame|filma|filmar|filmame) (?:un )?video|empezar video|grabar|filmar") { Camera(CameraAction.START_VIDEO) },
        rule("(?:termina|terminar|deten|detene|detener|corta|cortar|finaliza|finalizar|frena|frenar)(?: el| la)? (?:video|grabacion|filmacion)|(?:para|parar|termina|terminar|deja|dejar) de (?:grabar|filmar)|terminar video|cortar video") { Camera(CameraAction.STOP_VIDEO) },
        rule("(?:cambia|cambiar|gira|girar|da vuelta|dale vuelta|vuelta|rota|rotar|invertir|invierte)(?: de| a)?(?: la)? camara") { Camera(CameraAction.SWITCH) },

        // Llamadas
        rule("(?:atende|atender|atendela|contesta|contestar|contestala)(?: la llamada| el telefono)?") { AnswerCall },
        rule("(?:colga|colgar|corta la llamada|cortar la llamada|rechaza|rechazar|rechazala|termina la llamada|terminar la llamada|corta el telefono)(?: la llamada)?") { HangUp },
        rule("(?:llama|llamar|llamame a|llamale|llamala|llamalo|marca|marcar|marcale) (?:a |al |a la |al numero )?(.+)") { m, _ ->
            Call(m.groupValues[1]).takeIf { !Regex("\\b(?:y|despues|para)\\b").containsMatchIn(m.groupValues[1]) }
        },

        // Abrir una app por su nombre (corto: "abrí mercado libre", no "abrí el chat de Juan").
        rule("(?:abri|abrir|abrime|abre|lanza|inicia|entra a|entra en|anda a) (?:la app |la aplicacion |el |la )?([a-z0-9ñ]+(?: [a-z0-9ñ]+){0,2})") { m, _ ->
            val name = m.groupValues[1]
            if (Regex("\\b(?:y|de|del|para|con|que|a|en)\\b").containsMatchIn(name)) null else OpenApp(name)
        },

        // Ayuda
        rule("(?:que (?:puedo|podes|se puede) (?:decir|hacer|pedir)|ayuda|comandos|que comandos hay|que sabes hacer)") { Help },
    )

    private fun rule(pattern: String, build: (MatchResult, String) -> Command?): Pair<Regex, (MatchResult, String) -> Command?> =
        Regex(pattern) to build

    private fun rule(pattern: String, command: () -> Command): Pair<Regex, (MatchResult, String) -> Command?> =
        Regex(pattern) to { _, _ -> command() }

    /**
     * "Beto, escribí Hola, ¿cómo estás?" → "Hola, ¿cómo estás?": lo que sigue al
     * verbo, con tildes y mayúsculas como se dijo.
     */
    private fun textAfterVerb(original: String): String? {
        val words = original.trim().split(Regex("\\s+"))
        val verb = words.indexOfFirst { plain(it) in TYPE_VERBS }
        if (verb < 0) return null
        var rest = words.drop(verb + 1)
        if (rest.firstOrNull()?.let { plain(it) } == "que") rest = rest.drop(1)
        return rest.joinToString(" ").trim().takeIf { it.isNotEmpty() }
    }

    private val NUMBER_WORDS = mapOf(
        "uno" to 1, "una" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6,
        "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12,
        "trece" to 13, "catorce" to 14, "quince" to 15, "dieciseis" to 16, "diecisiete" to 17,
        "dieciocho" to 18, "diecinueve" to 19, "veinte" to 20, "treinta" to 30, "cuarenta" to 40,
        "cincuenta" to 50, "sesenta" to 60, "setenta" to 70, "ochenta" to 80, "noventa" to 90, "cien" to 100,
    )

    /**
     * Minúsculas, sin tildes ni signos, sin el nombre del asistente ni "por
     * favor", y con los números en cifras ("tocá el cinco" → "toca el 5").
     */
    fun normalize(text: String, agentName: String = ""): String {
        val name = plain(agentName)
        var words = plain(text).split(' ').filter { it.isNotEmpty() }
        if (name.isNotEmpty()) words = words.filter { it != name }
        var result = words.joinToString(" ")
            .replace(Regex("^(?:(?:che|dale|bueno|ok|y|ahora|porfa) )+"), "")
            .replace(Regex(" (?:por favor|porfa|porfis|gracias)$"), "")
            .trim()
        // "veinte y cinco" / "veinticinco" → 25 solo en los casos comunes; el resto, palabra por palabra.
        result = Regex("\\b(veinti|treinta y |cuarenta y |cincuenta y )(uno|dos|tres|cuatro|cinco|seis|siete|ocho|nueve)\\b")
            .replace(result) { m ->
                val tens = when (m.groupValues[1]) { "veinti" -> 20; "treinta y " -> 30; "cuarenta y " -> 40; else -> 50 }
                (tens + NUMBER_WORDS.getValue(m.groupValues[2])).toString()
            }
        return result.split(' ').joinToString(" ") { word ->
            // "una" queda como palabra salvo después de "el"/"numero" (tocá "una foto" no es 1).
            if (word == "una") word else NUMBER_WORDS[word]?.toString() ?: word
        }
    }

    private fun plain(text: String): String =
        Normalizer.normalize(text.lowercase(Locale("es")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9ñ ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
