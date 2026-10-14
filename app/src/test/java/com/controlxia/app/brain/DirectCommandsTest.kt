package com.controlxia.app.brain

import com.controlxia.app.brain.DirectCommands.Global
import com.controlxia.app.brain.DirectCommands.GlobalAction
import com.controlxia.app.brain.DirectCommands.Scroll
import com.controlxia.app.brain.DirectCommands.Direction
import com.controlxia.app.brain.DirectCommands.Volume
import com.controlxia.app.brain.DirectCommands.VolumeChange
import com.controlxia.app.brain.DirectCommands.Media
import com.controlxia.app.brain.DirectCommands.MediaAction
import com.controlxia.app.brain.DirectCommands.Flashlight
import com.controlxia.app.brain.DirectCommands.Toggle
import com.controlxia.app.brain.DirectCommands.Setting
import com.controlxia.app.brain.DirectCommands.Brightness
import com.controlxia.app.brain.DirectCommands.Numbers
import com.controlxia.app.brain.DirectCommands.TapNumber
import com.controlxia.app.brain.DirectCommands.TapLabel
import com.controlxia.app.brain.DirectCommands.Type
import com.controlxia.app.brain.DirectCommands.ClearText
import com.controlxia.app.brain.DirectCommands.Enter
import com.controlxia.app.brain.DirectCommands.Camera
import com.controlxia.app.brain.DirectCommands.CameraAction
import com.controlxia.app.brain.DirectCommands.StopVideoOrPause
import com.controlxia.app.brain.DirectCommands.Help
import com.controlxia.app.brain.DirectCommands.OpenApp
import com.controlxia.app.brain.DirectCommands.PreviousApp
import com.controlxia.app.brain.DirectCommands.Edit
import com.controlxia.app.brain.DirectCommands.TextEdit
import com.controlxia.app.brain.DirectCommands.Battery
import com.controlxia.app.brain.DirectCommands.SettingsScreen
import com.controlxia.app.brain.DirectCommands.QuickTile
import com.controlxia.app.brain.DirectCommands.Call
import com.controlxia.app.brain.DirectCommands.AnswerCall
import com.controlxia.app.brain.DirectCommands.HangUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las frases que el teléfono resuelve solo, sin el modelo (y las que tienen que ir al modelo: null). */
class DirectCommandsTest {

    @Test
    fun parsesPhrases() {
        val cases: List<Pair<String, DirectCommands.Command?>> = listOf(
            "atrás" to Global(GlobalAction.BACK), "volvé" to Global(GlobalAction.BACK), "Beto, atrás" to Global(GlobalAction.BACK),
            "andá al inicio" to Global(GlobalAction.HOME), "pantalla de inicio" to Global(GlobalAction.HOME),
            "mostrame las apps recientes" to Global(GlobalAction.RECENTS), "bajá las notificaciones" to Global(GlobalAction.NOTIFICATIONS),
            "abrí las notificaciones" to Global(GlobalAction.NOTIFICATIONS), "leeme las notificaciones" to null,
            "apagá la alarma" to DirectCommands.AlarmControl(DirectCommands.AlarmAction.STOP),
            "Beto pará la alarma" to DirectCommands.AlarmControl(DirectCommands.AlarmAction.STOP),
            "apagá el temporizador" to DirectCommands.AlarmControl(DirectCommands.AlarmAction.STOP),
            "posponela" to DirectCommands.AlarmControl(DirectCommands.AlarmAction.SNOOZE),
            "posponé la alarma 10 minutos" to DirectCommands.AlarmControl(DirectCommands.AlarmAction.SNOOZE),
            "cinco minutos más" to DirectCommands.AlarmControl(DirectCommands.AlarmAction.SNOOZE),
            "¿a qué hora es mi próxima alarma?" to DirectCommands.AlarmControl(DirectCommands.AlarmAction.NEXT),
            "qué alarmas tengo" to DirectCommands.AlarmControl(DirectCommands.AlarmAction.NEXT),
            "¿tengo alguna alarma puesta?" to DirectCommands.AlarmControl(DirectCommands.AlarmAction.NEXT),
            "a qué hora me despertás" to DirectCommands.AlarmControl(DirectCommands.AlarmAction.NEXT),
            "cancelá las alarmas" to DirectCommands.AlarmControl(DirectCommands.AlarmAction.CANCEL),
            "borrá la alarma de las 7" to DirectCommands.AlarmControl(DirectCommands.AlarmAction.CANCEL),
            "poné una alarma a las 7" to null, "apagá el wifi" to Toggle(Setting.WIFI, false),
            "borrá las notificaciones" to null, "abrí la notificación de Gmail" to null, "en Spotify tocá Biblioteca" to null,
            "abrí Spotify y tocá Biblioteca" to null, "mandale a Juan por Telegram que llego tarde" to null, "ajustes rápidos" to Global(GlobalAction.QUICK_SETTINGS),
            "bloqueá el teléfono" to Global(GlobalAction.LOCK), "sacá una captura de pantalla" to Global(GlobalAction.SCREENSHOT),
            "captura" to Global(GlobalAction.SCREENSHOT), "bajá" to Scroll(Direction.DOWN), "seguí bajando" to Scroll(Direction.DOWN),
            "subí" to Scroll(Direction.UP), "deslizá a la izquierda" to Scroll(Direction.LEFT), "pasá a la derecha" to Scroll(Direction.RIGHT),
            "subí el volumen" to Volume(VolumeChange.UP), "subí el volumen un poco" to Volume(VolumeChange.UP, steps = 1),
            "bajá el volumen" to Volume(VolumeChange.DOWN), "bajale el volumen bastante" to Volume(VolumeChange.DOWN, steps = 4),
            "volumen al máximo" to Volume(VolumeChange.SET, 100), "volumen a la mitad" to Volume(VolumeChange.SET, 50),
            "volumen al 30 por ciento" to Volume(VolumeChange.SET, 30), "volumen al treinta" to Volume(VolumeChange.SET, 30),
            "silenciá" to Volume(VolumeChange.MUTE), "poné en silencio el teléfono" to Volume(VolumeChange.MUTE),
            "pausa" to Media(MediaAction.PAUSE), "poné pausa" to Media(MediaAction.PAUSE), "pausá el video" to Media(MediaAction.PAUSE),
            "pará el video" to StopVideoOrPause, "pará la música" to Media(MediaAction.PAUSE),
            "dale play" to Media(MediaAction.PLAY), "reproducí" to Media(MediaAction.PLAY), "siguiente canción" to Media(MediaAction.NEXT),
            "pasá el tema" to Media(MediaAction.NEXT), "canción anterior" to Media(MediaAction.PREVIOUS),
            "prendé la linterna" to Flashlight(true), "apagá la linterna" to Flashlight(false), "linterna" to Flashlight(null),
            "activá el wifi" to Toggle(Setting.WIFI, true), "apagá el wi-fi" to Toggle(Setting.WIFI, false),
            "prendé el bluetooth" to Toggle(Setting.BLUETOOTH, true), "activá el modo avión" to Toggle(Setting.AIRPLANE, true),
            "desactivá los datos móviles" to Toggle(Setting.MOBILE_DATA, false),
            "subí el brillo" to Brightness(true), "bajá el brillo" to Brightness(false), "brillo al máximo" to Brightness(true, 100),
            "mostrá los números" to Numbers(true), "números" to Numbers(true), "ocultá los números" to Numbers(false),
            "tocá el 5" to TapNumber(5), "tocá el cinco" to TapNumber(5), "tocá 12" to TapNumber(12), "presioná el número veinticinco" to TapNumber(25),
            "mantené apretado el 3" to TapNumber(3, long = true), "tocá Guardar" to TapLabel("guardar"),
            "tocá el botón de enviar" to TapLabel("enviar"), "tocá en Reels" to TapLabel("reels"), "mantené presionado el mensaje" to TapLabel("mensaje", long = true),
            "escribí Hola, ¿cómo estás?" to Type("Hola, ¿cómo estás?"), "Beto, escribí que llego tarde" to Type("llego tarde"),
            "borrá todo" to ClearText, "enter" to Enter, "dale enter" to Enter,
            "abrí la cámara" to Camera(CameraAction.OPEN), "cámara" to Camera(CameraAction.OPEN), "abrí la cámara de video" to Camera(CameraAction.OPEN_VIDEO),
            "cámara frontal" to Camera(CameraAction.SELFIE), "sacá una foto" to Camera(CameraAction.PHOTO), "sacame una selfie" to Camera(CameraAction.SELFIE_PHOTO),
            "selfie" to Camera(CameraAction.SELFIE_PHOTO), "empezá a grabar" to Camera(CameraAction.START_VIDEO), "empezar video" to Camera(CameraAction.START_VIDEO),
            "grabá un video" to Camera(CameraAction.START_VIDEO), "terminá el video" to Camera(CameraAction.STOP_VIDEO), "terminar video" to Camera(CameraAction.STOP_VIDEO),
            "pará de grabar" to Camera(CameraAction.STOP_VIDEO), "detené la grabación" to Camera(CameraAction.STOP_VIDEO), "cambiá de cámara" to Camera(CameraAction.SWITCH),
            "dale vuelta la cámara" to Camera(CameraAction.SWITCH),
            "abrí Instagram" to OpenApp("instagram"), "abrí mercado libre" to OpenApp("mercado libre"), "entrá a YouTube" to OpenApp("youtube"),
            "¿qué puedo decir?" to Help,
            "llamá a mamá" to Call("mama"), "Beto, llamá a Juan Pérez" to Call("juan perez"), "llamá al 11 5555 4444" to Call("11 5555 4444"),
            "atendé" to AnswerCall, "contestá la llamada" to AnswerCall, "colgá" to HangUp, "rechazá la llamada" to HangUp, "cortá la llamada" to HangUp,
            "llamá a Juan y decile que llego tarde" to null,
            "activá no molestar" to QuickTile("no molestar", true), "desactivá la rotación automática" to QuickTile("rotacion automatica", false),
            "activá el ahorro de batería" to QuickTile("ahorro de bateria", true), "apagá la pantalla" to Global(GlobalAction.LOCK),
            "activá los números" to Numbers(true), "activá el modo avión" to Toggle(Setting.AIRPLANE, true),
            "abrí los ajustes de batería" to SettingsScreen("bateria"), "configuración" to SettingsScreen(""), "ajustes de wifi" to SettingsScreen("wifi"),
            "¿cuánta batería me queda?" to Battery, "batería" to Battery,
            "copiá todo" to TextEdit(Edit.COPY), "pegá" to TextEdit(Edit.PASTE), "seleccioná todo" to TextEdit(Edit.SELECT_ALL),
            "volvé a la app anterior" to PreviousApp, "cómo se llama esta canción" to null,
            // Tienen que ir al modelo:
            "abrí el chat de Juan" to null, "abrí instagram y mirá los reels" to null, "mandale hola a Juan" to null,
            "poné una alarma a las 7" to null, "qué hora es" to null, "5" to null, "leeme las notificaciones" to null,
            "buscá zapatillas nike en mercado libre" to null, "seguí" to null, "siguiente" to null, "subí la foto a instagram" to null,
            "bajá la app de netflix" to null, "escribí decirle estos últimos dos mensajes lo envié con la aplicación" to null,
            "escribí a Rebeca que llego tarde" to null, "abrí whatsapp de Juan" to null, "poné música" to null, "escribile a Juan que llego tarde" to null,
        )
        val wrong = cases.mapNotNull { (text, want) ->
            val got = DirectCommands.parse(text, agentName = "Beto")
            if (got == want) null else "«$text» → $got (esperaba $want)"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun bareNumberTapsOnlyWithNumbersVisible() {
        assertEquals(TapNumber(5), DirectCommands.parse("5", numbersVisible = true))
        assertEquals(null, DirectCommands.parse("5"))
    }
}
