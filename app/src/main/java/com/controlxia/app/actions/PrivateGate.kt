package com.controlxia.app.actions

import android.content.Context
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.voice.LockedAccess
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
            LockedAccess.ASK_UNLOCK -> if (UnlockGate.unlock(context, why)) null else UnlockGate.NOT_UNLOCKED
            LockedAccess.BLOCKED -> {
                AgentLog.add("PRIVADO bloqueado: $why")
                BLOCKED_REPLY
            }
        }
    }

    const val BLOCKED_REPLY =
        "Con el teléfono bloqueado no leo ni mando mensajes, ni llamo. Desbloquealo y pedímelo de nuevo."
}
