package com.controlxia.app.brain

import android.content.Context
import com.controlxia.app.actions.AlarmActions
import com.controlxia.app.actions.AppLauncher
import com.controlxia.app.actions.CameraControl
import com.controlxia.app.actions.Contacts
import com.controlxia.app.actions.DeviceControl
import com.controlxia.app.actions.PhoneCalls
import com.controlxia.app.actions.ScreenNumbers
import com.controlxia.app.actions.UnlockGate
import com.controlxia.app.brain.DirectCommands.CameraAction
import com.controlxia.app.brain.DirectCommands.Command
import com.controlxia.app.diagnostics.AgentLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ejecuta los comandos directos ([DirectCommands]). Como el Acceso por voz,
 * no habla cuando el resultado se ve ("atrás", "bajá", "tocá 5"): muestra un
 * texto corto y sigue escuchando. Habla si algo falla o si no se ve (empezar
 * a grabar, ayuda).
 */
class DirectCommandRunner(private val context: Context) {

    private val device by lazy { DeviceControl(context) }
    private val camera by lazy { CameraControl(context) }
    private val apps by lazy { AppLauncher(context) }
    private val calls by lazy { PhoneCalls(context) }
    private val contacts by lazy { Contacts(context) }
    private val alarms by lazy { AlarmActions(context) }

    /** Ejecuta [command]. null = no se pudo resolver acá: que lo intente el modelo. */
    suspend fun run(command: Command): Reply? {
        AgentLog.add("DIRECTO $command")
        // Con el teléfono bloqueado, lo que necesita la pantalla pide la huella primero.
        unlockReason(command)?.let { why ->
            if (!UnlockGate.unlock(context, why)) return Reply(UnlockGate.NOT_UNLOCKED)
        }
        return when (command) {
            is DirectCommands.Global -> {
                val label = GLOBAL_LABELS.getValue(command.action)
                done(device.global(DeviceControl.Global.valueOf(command.action.name)), label)
            }
            is DirectCommands.Scroll -> done(
                device.scroll(DeviceControl.Direction.valueOf(command.direction.name)),
                SCROLL_LABELS.getValue(command.direction),
            )
            is DirectCommands.Volume -> when (command.change) {
                DirectCommands.VolumeChange.UP -> done(device.volumeStep(command.steps), "Volumen +")
                DirectCommands.VolumeChange.DOWN -> done(device.volumeStep(-command.steps), "Volumen −")
                DirectCommands.VolumeChange.SET -> done(device.volumeSet(command.percent), "Volumen ${command.percent} %")
                DirectCommands.VolumeChange.MUTE -> done(device.mute(true), "Sin sonido")
                DirectCommands.VolumeChange.UNMUTE -> done(device.mute(false), "Con sonido")
            }
            is DirectCommands.Media -> {
                val (key, label) = when (command.action) {
                    DirectCommands.MediaAction.PLAY -> DeviceControl.Media.PLAY to "▶ Play"
                    DirectCommands.MediaAction.PAUSE -> DeviceControl.Media.PAUSE to "⏸ Pausa"
                    DirectCommands.MediaAction.NEXT -> DeviceControl.Media.NEXT to "⏭ Siguiente"
                    DirectCommands.MediaAction.PREVIOUS -> DeviceControl.Media.PREVIOUS to "⏮ Anterior"
                }
                done(device.media(key), label)
            }
            DirectCommands.StopVideoOrPause ->
                if (CameraControl.recording) stopVideo() else done(device.media(DeviceControl.Media.PAUSE), "⏸ Pausa")
            is DirectCommands.Flashlight -> when (command.on) {
                true -> done(device.flashlight(true), "Linterna prendida")
                false -> done(device.flashlight(false), "Linterna apagada")
                null -> done(device.toggleFlashlight(), "Linterna")
            }
            is DirectCommands.Toggle -> {
                val name = SETTING_NAMES.getValue(command.setting)
                done(
                    device.setToggle(DeviceControl.Toggle.valueOf(command.setting.name), command.on),
                    "$name ${if (command.on) "prendido" else "apagado"}",
                )
            }
            is DirectCommands.Brightness -> {
                val result = command.percent?.let { device.brightness(it) } ?: device.brightnessStep(command.up)
                done(result, command.percent?.let { "Brillo $it %" } ?: if (command.up) "Brillo +" else "Brillo −")
            }
            is DirectCommands.Numbers ->
                if (command.show) {
                    done(ScreenNumbers.show(), "Decí «tocá» y el número")
                } else {
                    ScreenNumbers.hide()
                    done(null, "Números ocultos")
                }
            is DirectCommands.TapNumber -> done(
                ScreenNumbers.tap(command.number, command.long),
                "${if (command.long) "Mantuve" else "Toqué"} el ${command.number}",
            )
            is DirectCommands.TapLabel -> {
                // Si no está en pantalla, puede ser otra cosa ("tocá la guitarra"): al modelo.
                if (ScreenNumbers.findByLabel(command.label) == null) return null
                done(ScreenNumbers.tapLabel(command.label, command.long), "Toqué «${command.label}»")
            }
            // Dictar solo con un campo abierto; si no, "escribí…" es un pedido para el modelo.
            is DirectCommands.Type -> {
                if (!ScreenNumbers.hasFocusedField()) return null
                done(ScreenNumbers.type(command.text), "Escribí: ${command.text}")
            }
            DirectCommands.ClearText -> done(ScreenNumbers.type("", replace = true), "Borré el texto")
            DirectCommands.Enter -> done(ScreenNumbers.enter(), "Enter")
            is DirectCommands.Camera -> camera(command.action)
            is DirectCommands.OpenApp -> {
                var result = apps.open(command.name)
                // Sin una app con ese nombre puede ser otra cosa ("abrí el mapa"): al modelo.
                if (result.startsWith("No encontré la app")) return null
                // Existe pero está bloqueado: pedir la huella y abrirla.
                if (UnlockGate.isLockedMessage(result)) {
                    if (!UnlockGate.unlock(context, "para abrir ${command.name}")) return Reply(UnlockGate.NOT_UNLOCKED)
                    result = apps.open(command.name)
                }
                if (result.startsWith("Abriendo")) Reply("", shown = result) else Reply(result)
            }
            DirectCommands.Help -> Reply(HELP)
            is DirectCommands.QuickTile -> {
                val result = device.quickTile(command.name, command.on)
                // No hay un botón así: puede ser otra cosa ("activá el modo oscuro de WhatsApp").
                if (result == DeviceControl.TILE_NOT_FOUND) return null
                done(result, "${command.name.replaceFirstChar { it.uppercase() }} ${if (command.on) "activado" else "desactivado"}")
            }
            is DirectCommands.SettingsScreen -> done(device.openSettings(command.which), "Ajustes")
            DirectCommands.Battery -> Reply(device.batteryStatus())
            is DirectCommands.TextEdit -> done(
                device.edit(DeviceControl.Edit.valueOf(command.action.name)),
                EDIT_LABELS.getValue(command.action),
            )
            DirectCommands.PreviousApp -> done(device.previousApp(), "App anterior")
            is DirectCommands.Call -> call(command.who)
            DirectCommands.AnswerCall -> done(calls.answer(), "Llamada atendida")
            DirectCommands.HangUp -> done(calls.hangUp(), "Llamada cortada")
            is DirectCommands.AlarmControl -> when (command.action) {
                DirectCommands.AlarmAction.STOP -> alarms.stopRinging().let { if (it.isBlank()) Reply("", shown = "⏰ Alarma apagada") else Reply(it) }
                DirectCommands.AlarmAction.SNOOZE -> Reply(alarms.snooze())
                DirectCommands.AlarmAction.NEXT -> Reply(alarms.nextAlarm())
                DirectCommands.AlarmAction.CANCEL -> Reply(alarms.cancelOwn())
            }
        }
    }

    /** Por qué hay que desbloquear para [command], o null si anda bloqueado (o no le importa). */
    private fun unlockReason(command: Command): String? = when (command) {
        is DirectCommands.Toggle, is DirectCommands.SettingsScreen -> "para cambiar los ajustes"
        DirectCommands.PreviousApp -> "para volver a la app anterior"
        is DirectCommands.Camera -> when (command.action) {
            // La foto anda sobre el bloqueo (cámara segura); el resto necesita la cámara completa.
            CameraAction.OPEN_VIDEO, CameraAction.SELFIE, CameraAction.START_VIDEO -> "para usar la cámara"
            else -> null
        }
        else -> null
    }

    private suspend fun camera(action: CameraAction): Reply = when (action) {
        CameraAction.OPEN -> done(camera.open(CameraControl.Mode.PHOTO), "Cámara")
        CameraAction.OPEN_VIDEO -> done(camera.open(CameraControl.Mode.VIDEO), "Cámara de video")
        CameraAction.SELFIE -> done(camera.open(CameraControl.Mode.SELFIE), "Cámara frontal")
        CameraAction.PHOTO -> done(camera.takePhoto(), "📷 Foto")
        CameraAction.SELFIE_PHOTO -> done(camera.takePhoto(selfie = true), "📷 Selfie")
        CameraAction.SWITCH -> done(camera.switchCamera(), "Cámara cambiada")
        CameraAction.START_VIDEO -> {
            val error = camera.startVideo()
            // Sin hablar (se grabaría la voz del asistente) y sin seguir escuchando:
            // mientras graba, el asistente solo oye "terminá el video".
            if (error == null) {
                Reply("", endsConversation = true, shown = "● Grabando: decí «terminá el video»")
            } else {
                Reply(error)
            }
        }
        CameraAction.STOP_VIDEO -> stopVideo()
    }

    /**
     * "Llamá a mamá": si el contacto es uno solo, llama; si hay dudas (varios
     * parecidos, no está), que lo resuelva el modelo preguntando.
     */
    private suspend fun call(who: String): Reply? {
        val digits = who.filter { it.isDigit() }
        if (digits.length >= 6 && digits.length >= who.count { !it.isWhitespace() } - 2) {
            return calls.call(digits)?.let { Reply(it) } ?: Reply("", shown = "Llamando al $digits")
        }
        val found = withContext(Dispatchers.IO) { contacts.find(who) }
        val contact = (found as? Contacts.Lookup.Found)?.contact ?: return null
        return calls.call(contact.phone)?.let { Reply(it) } ?: Reply("", shown = "Llamando a ${contact.name}")
    }

    private suspend fun stopVideo(): Reply {
        val error = camera.stopVideo()
        return Reply(error ?: "Listo, el video quedó guardado.")
    }

    private fun done(error: String?, shown: String): Reply =
        if (error == null) Reply("", shown = shown) else Reply(error)

    private companion object {
        val GLOBAL_LABELS = mapOf(
            DirectCommands.GlobalAction.BACK to "← Atrás",
            DirectCommands.GlobalAction.HOME to "Inicio",
            DirectCommands.GlobalAction.RECENTS to "Recientes",
            DirectCommands.GlobalAction.NOTIFICATIONS to "Notificaciones",
            DirectCommands.GlobalAction.QUICK_SETTINGS to "Ajustes rápidos",
            DirectCommands.GlobalAction.LOCK to "Bloqueado",
            DirectCommands.GlobalAction.SCREENSHOT to "Captura guardada",
            DirectCommands.GlobalAction.POWER_MENU to "Menú de apagado",
            DirectCommands.GlobalAction.SPLIT_SCREEN to "Pantalla dividida",
        )
        val SCROLL_LABELS = mapOf(
            DirectCommands.Direction.DOWN to "↓",
            DirectCommands.Direction.UP to "↑",
            DirectCommands.Direction.LEFT to "←",
            DirectCommands.Direction.RIGHT to "→",
        )
        val EDIT_LABELS = mapOf(
            DirectCommands.Edit.COPY to "Copiado",
            DirectCommands.Edit.PASTE to "Pegado",
            DirectCommands.Edit.CUT to "Cortado",
            DirectCommands.Edit.SELECT_ALL to "Todo seleccionado",
        )
        val SETTING_NAMES = mapOf(
            DirectCommands.Setting.WIFI to "Wi-Fi",
            DirectCommands.Setting.BLUETOOTH to "Bluetooth",
            DirectCommands.Setting.AIRPLANE to "Modo avión",
            DirectCommands.Setting.MOBILE_DATA to "Datos",
        )
        const val HELP = "Sin internet para el modelo podés decir: mandale a Juan que llego tarde, " +
            "por Telegram también, mandale un audio a Juan, llamá a mamá, leeme los mensajes o las " +
            "notificaciones, respondele que sí, poné una alarma a las 7, apagá la alarma, temporizador de 5 minutos, " +
            "agendá dentista el jueves a las 4, cuánto es 25 por 4, buscá pizza en cualquier app, en " +
            "Spotify tocá Biblioteca, abrí los reels, sacá una foto, activá no molestar o el wifi, " +
            "subí el volumen, pausa, y para cualquier pantalla: mostrá los números y tocá uno, bajá, " +
            "atrás, inicio. Bloqueado también: si hace falta te pido la huella. Para frenarme, decí pará."
    }
}
