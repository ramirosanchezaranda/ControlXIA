package com.controlxia.app.actions

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.permissions.PermissionManager
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * Alarmas y temporizadores. Primero en la app de Reloj (así aparecen ahí),
 * con los intents de `AlarmClock`; si el Reloj no la toma —no hay, la rechaza,
 * el teléfono está bloqueado o falta el permiso de mostrar sobre otras apps—,
 * la pone ControlXIA con [OwnAlarms], que suena igual. Para saber si el Reloj
 * la puso se mira la próxima alarma del sistema, que el Reloj actualiza.
 */
class AlarmActions(private val context: Context) {

    private val manager get() = context.getSystemService(AlarmManager::class.java)

    /** Alarma a una hora del día (la próxima vez que llegue esa hora). */
    suspend fun setAlarm(hour: Int, minute: Int, label: String?): String {
        if (hour !in 0..23 || minute !in 0..59) return "Esa hora no es válida"
        val target = nextOccurrence(hour, minute)
        val viaClock = clockCanTake(AlarmClock.ACTION_SET_ALARM) && withClock(target) {
            Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            }
        }
        if (!viaClock) OwnAlarms.schedule(context, target, label.orEmpty(), timer = false)
        AgentLog.add("ALARMA ${if (viaClock) "en el Reloj" else "propia"} ${"%02d:%02d".format(hour, minute)}")
        val whenText = describeWhen(target)
        return if (label.isNullOrBlank()) "Listo, alarma $whenText." else "Listo, alarma «$label» $whenText."
    }

    /** Temporizador de una cantidad de segundos. */
    suspend fun setTimer(seconds: Int, label: String?): String {
        if (seconds <= 0) return "Esa duración no es válida"
        if (seconds > MAX_TIMER_SECONDS) return "El temporizador puede durar hasta 24 horas"
        // Los temporizadores del Reloj no se ven desde afuera: si se pudo abrir, se confía.
        val viaClock = clockCanTake(AlarmClock.ACTION_SET_TIMER) && launch(
            Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            },
        )
        if (!viaClock) OwnAlarms.schedule(context, System.currentTimeMillis() + seconds * 1_000L, label.orEmpty(), timer = true)
        AgentLog.add("TEMPORIZADOR ${if (viaClock) "en el Reloj" else "propio"} ${seconds}s")
        return "Temporizador de ${describeDuration(seconds)} iniciado"
    }

    /** "Apagá la alarma": la propia que suena, o la del Reloj. */
    fun stopRinging(): String {
        if (OwnAlarms.stop(context)) return ""
        val sent = launch(Intent(AlarmClock.ACTION_DISMISS_ALARM).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        return if (sent) "" else "No hay ninguna alarma sonando."
    }

    /** "Posponé la alarma". */
    fun snooze(): String {
        if (OwnAlarms.snooze(context)) return "Listo, te la pospongo 10 minutos."
        val sent = launch(Intent(AlarmClock.ACTION_SNOOZE_ALARM).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        return if (sent) "Listo, pospuesta." else "No hay ninguna alarma sonando."
    }

    /** "¿A qué hora es mi próxima alarma?" (las del Reloj y las propias). */
    fun nextAlarm(): String {
        val next = manager.nextAlarmClock ?: return "No tenés ninguna alarma puesta."
        return "Tu próxima alarma es ${describeWhen(next.triggerTime)}."
    }

    /** "Cancelá las alarmas": las que puso ControlXIA (las del Reloj se borran ahí). */
    fun cancelOwn(): String {
        val count = OwnAlarms.cancelAll(context)
        return when (count) {
            0 -> "No tengo alarmas mías puestas. Las del Reloj se borran desde la app Reloj."
            1 -> "Listo, cancelé la alarma."
            else -> "Listo, cancelé $count alarmas."
        }
    }

    private fun clockCanTake(action: String): Boolean =
        PermissionManager.canLaunchActivities(context) &&
            !UnlockGate.isLocked(context) &&
            Intent(action).resolveActivity(context.packageManager) != null

    /**
     * Le pasa la alarma al Reloj y espera a que la próxima alarma del sistema
     * cambie (o sea la pedida). false = no se puede saber que la puso.
     */
    private suspend fun withClock(target: Long, intent: () -> Intent): Boolean {
        val before = manager.nextAlarmClock?.triggerTime
        if (!launch(intent())) return false
        repeat(VERIFY_TRIES) {
            delay(VERIFY_STEP_MS)
            val after = manager.nextAlarmClock?.triggerTime
            if (after != null && (after != before || kotlin.math.abs(after - target) < MINUTE_MS)) return true
        }
        // Ya había una alarma antes que la pedida: la próxima no cambia aunque el Reloj la haya puesto.
        return before != null && before < target - MINUTE_MS
    }

    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        // Sin permiso para crear alarmas en ese Reloj, o ninguna app la toma.
        AgentLog.add("ALARMA el Reloj no la tomó: ${e.javaClass.simpleName}")
        false
    }

    private fun nextOccurrence(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    /** "para hoy a las 07:30", "para mañana a las 07:30", "para el jueves a las 07:30". */
    private fun describeWhen(at: Long): String {
        val target = Calendar.getInstance().apply { timeInMillis = at }
        val today = Calendar.getInstance()
        val days = ((startOfDay(target) - startOfDay(today)) / DAY_MS).toInt()
        val clock = "%02d:%02d".format(target.get(Calendar.HOUR_OF_DAY), target.get(Calendar.MINUTE))
        val day = when (days) {
            0 -> "hoy"
            1 -> "mañana"
            in 2..6 -> "el " + java.text.SimpleDateFormat("EEEE", java.util.Locale("es")).format(target.time)
            else -> "el " + java.text.SimpleDateFormat("d 'de' MMMM", java.util.Locale("es")).format(target.time)
        }
        return "para $day a las $clock"
    }

    private fun startOfDay(cal: Calendar): Long = (cal.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

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
        const val VERIFY_TRIES = 10
        const val VERIFY_STEP_MS = 250L
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 86_400_000L
    }
}
