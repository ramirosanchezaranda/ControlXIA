package com.controlxia.app.actions

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

/**
 * Alarmas y temporizadores vía los intents estándar de `AlarmClock`. Con
 * `EXTRA_SKIP_UI` se crean **sin abrir la app de reloj**, así funcionan desde
 * el servicio en segundo plano y con la pantalla bloqueada (no lanzan Activity).
 */
class AlarmActions(private val context: Context) {

    /** Alarma a una hora del día. */
    fun setAlarm(hour: Int, minute: Int, label: String?): String {
        if (hour !in 0..23 || minute !in 0..59) return "Esa hora no es válida"
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
        val m = seconds / 60
        val s = seconds % 60
        return when {
            m > 0 && s > 0 -> "$m minutos y $s segundos"
            m > 0 -> "$m minutos"
            else -> "$s segundos"
        }
    }
}
