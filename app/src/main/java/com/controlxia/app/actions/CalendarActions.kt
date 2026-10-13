package com.controlxia.app.actions

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.permissions.PermissionManager
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Eventos y recordatorios en el calendario: abre la app de calendario con el
 * evento ya completado y toca "Guardar" con el control de apps.
 */
class CalendarActions(private val context: Context) {

    suspend fun addEvent(title: String, date: LocalDate, time: LocalTime): String {
        if (!PermissionManager.canLaunchActivities(context)) {
            return "Para abrir el calendario necesito el permiso Mostrar sobre otras apps."
        }
        val start = date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 60 * 60 * 1000)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(intent) }.isSuccess
        if (!opened) return "No encontré una app de calendario."
        val whenText = date.format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale("es"))) +
            " a las " + time.format(DateTimeFormatter.ofPattern("HH:mm"))
        AgentLog.add("CALENDARIO «$title» $whenText")
        // El editor tarda en aparecer; después se toca Guardar.
        repeat(8) {
            delay(500)
            val save = listOf("Guardar", "Save", "Listo", "Hecho").firstNotNullOfOrNull { ScreenNumbers.findByLabel(it) }
            if (save != null) {
                ScreenNumbers.tapLabel(save.text?.toString() ?: save.contentDescription?.toString() ?: "Guardar")
                return "Listo, agendé «$title» para el $whenText."
            }
        }
        return "Te dejé «$title» listo para el $whenText: tocá guardar."
    }
}
