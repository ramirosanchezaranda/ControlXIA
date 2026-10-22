package com.controlxia.app.actions

import android.content.Context
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.voice.LockedAccess
import com.controlxia.app.voice.VoiceBridge
import com.controlxia.app.voice.VoiceId
import com.controlxia.app.voice.WakeWordSettings

/**
 * Lo privado con el teléfono bloqueado: leer mensajes y notificaciones,
 * mandar, responder y llamar. Cualquiera que diga el nombre cerca de un
 * teléfono bloqueado podría pedirlo, así que según el ajuste se pide la huella
 * (por defecto), no se hace, o se hace igual.
 */
object PrivateGate {

    /** null si se puede seguir; si no, lo que hay que decirle al usuario. [why]: "para leerte los mensajes". */
    suspend fun check(context: Context, why: String): String? {
        if (!UnlockGate.isLocked(context)) return null
        return when (WakeWordSettings(context).lockedAccess) {
            LockedAccess.ALLOWED -> null
            LockedAccess.ASK_UNLOCK -> unlock(context, why)
            LockedAccess.VOICE -> voice(context, why)
            LockedAccess.BLOCKED -> {
                AgentLog.add("PRIVADO bloqueado: $why")
                BLOCKED_REPLY
            }
        }
    }

    private suspend fun unlock(context: Context, why: String): String? =
        if (UnlockGate.unlock(context, why)) null else UnlockGate.NOT_UNLOCKED

    /** Tu contraseña dicha con tu voz; dos intentos y, si no, la huella. */
    private suspend fun voice(context: Context, why: String): String? {
        if (VoiceId.verifiedRecently()) return null
        if (!VoiceId.isEnrolled(context) || !VoiceId.isSpeakerModelReady(context)) return unlock(context, why)
        repeat(ATTEMPTS) { attempt ->
            VoiceBridge.say(if (attempt == 0) "Decí tu contraseña $why." else "No te reconocí. Decila de nuevo.")
            val check = VoiceBridge.withMicrophone { VoiceId.verify(context) } ?: return unlock(context, why)
            if (check.ok) {
                AgentLog.add("PRIVADO: te reconocí por la voz")
                return null
            }
        }
        AgentLog.add("PRIVADO: no reconocí la voz, pido la huella")
        return unlock(context, why)
    }

    private const val ATTEMPTS = 2

    const val BLOCKED_REPLY =
        "Con el teléfono bloqueado no leo ni mando mensajes, ni llamo. Desbloquealo y pedímelo de nuevo."
}
