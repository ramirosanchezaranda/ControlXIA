package com.controlxia.app.actions

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.permissions.PermissionManager

/**
 * Llamadas por voz: llamar a un contacto o número, atender y colgar. Atender
 * y colgar usan el permiso de "administrar llamadas"; si falta, se avisa.
 */
class PhoneCalls(private val context: Context) {

    /** Llama a [number]. Sin el permiso de llamar, abre el marcador con el número. null si salió bien. */
    @SuppressLint("MissingPermission")
    fun call(number: String): String? {
        val uri = Uri.fromParts("tel", number.filter { it.isDigit() || it == '+' }, null)
        val direct = PermissionManager.hasCallPhone(context)
        if (!direct && !PermissionManager.canLaunchActivities(context)) {
            return "Para llamar necesito el permiso de llamadas: activalo en ControlXIA."
        }
        val intent = Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            AgentLog.add("LLAMADA ${if (direct) "a" else "marcador con"} $number")
            if (direct) null else "Te dejé el número marcado: tocá llamar (o activá el permiso de llamadas)."
        }.getOrElse { "No pude hacer la llamada." }
    }

    /** Atiende la llamada que está sonando. */
    @SuppressLint("MissingPermission")
    fun answer(): String? {
        if (!PermissionManager.hasAnswerCalls(context)) return NEEDS_PERMISSION
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        return runCatching {
            @Suppress("DEPRECATION")
            telecom.acceptRingingCall()
            AgentLog.add("LLAMADA atendida")
            null
        }.getOrElse { "No pude atender." }
    }

    /** Corta o rechaza la llamada. */
    @SuppressLint("MissingPermission")
    fun hangUp(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "Esta versión de Android no deja cortar llamadas."
        if (!PermissionManager.hasAnswerCalls(context)) return NEEDS_PERMISSION
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        return runCatching {
            @Suppress("DEPRECATION")
            val ended = telecom.endCall()
            AgentLog.add("LLAMADA cortada: $ended")
            if (ended) null else "No hay ninguna llamada para cortar."
        }.getOrElse { "No pude cortar la llamada." }
    }

    private companion object {
        const val NEEDS_PERMISSION = "Para atender o cortar necesito el permiso de llamadas: activalo en ControlXIA."
    }
}
