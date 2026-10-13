package com.controlxia.app.actions

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.controlxia.app.permissions.PermissionManager

/**
 * Alarmas y temporizadores vía los intents estándar de `AlarmClock`. Con
 * `EXTRA_SKIP_UI` la app de reloj los crea sin mostrarse, pero igual es un
 * `startActivity`: desde segundo plano Android lo ignora en silencio salvo que
 * haya permiso "Mostrar sobre otras apps". Por eso se verifica antes, para no
 * decir "alarma puesta" cuando no se puso.
 */
class AlarmActions(private val context: Context) {

    /** Alarma a una hora del día. */
    fun setAlarm(hour: Int, minute: Int, label: String?): String {
        if (hour !in 0..23 || minute !in 0..59) return "Esa hora no es válida"
        if (!PermissionManager.canLaunchActivities(context)) return MISSING_PERMISSION
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            val hh = "%02d:%02d".format(hour, minute)
            if (label.isNullOrBlank()) "Alarma puesta para las $hh"
            else "Alarma “$label” puesta para las $hh"
        } catch (e: Exception) {
            "No pude poner la alarma"
        }
    }

    /** Temporizador de una cantidad de segundos. */
    fun setTimer(seconds: Int, label: String?): String {
        if (seconds <= 0) return "Esa duración no es válida"
        if (seconds > MAX_TIMER_SECONDS) return "El temporizador puede durar hasta 24 horas"
        if (!PermissionManager.canLaunchActivities(context)) return MISSING_PERMISSION
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Temporizador de ${describeDuration(seconds)} iniciado"
        } catch (e: Exception) {
            "No pude iniciar el temporizador"
        }
    }

    private fun describeDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val parts = buildList {
            if (h > 0) add(if (h == 1) "1 hora" else "$h horas")
            if (m > 0) add(if (m == 1) "1 minuto" else "$m minutos")
            if (s > 0) add(if (s == 1) "1 segundo" else "$s segundos")
        }
        return when (parts.size) {
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + " y " + parts.last()
        }
    }

    private companion object {
        /** Tope de EXTRA_LENGTH en AlarmClock. */
        const val MAX_TIMER_SECONDS = 86_400
        const val MISSING_PERMISSION =
            "Para eso necesito el permiso Mostrar sobre otras apps. Activalo en ControlXIA."
    }
}
