package com.controlxia.app.actions

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.controlxia.app.R
import com.controlxia.app.XiaApplication
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.permissions.PermissionManager
import com.controlxia.app.service.AlarmReceiver
import com.controlxia.app.ui.AlarmRingActivity
import com.controlxia.app.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Alarmas y temporizadores propios, para cuando la app de Reloj no los toma
 * (no está, no deja crearlas desde otra app, el teléfono está bloqueado o
 * falta el permiso de mostrar sobre otras apps). Usan el mismo mecanismo que
 * el Reloj (`setAlarmClock`: exactas, aunque el teléfono duerma, y con el
 * ícono de alarma en la barra) y suenan con el sonido de alarma del sistema,
 * en una pantalla sobre el bloqueo con "Detener" y "Posponer". Sobreviven a
 * un reinicio.
 */
object OwnAlarms {

    data class Alarm(val id: Int, val triggerAt: Long, val label: String, val timer: Boolean)

    private val _ringing = MutableStateFlow<Alarm?>(null)

    /** La alarma que está sonando ahora (o null). */
    val ringing: StateFlow<Alarm?> = _ringing.asStateFlow()

    /** Avisa al servicio de voz que empezó o terminó de sonar (para oír "apagá la alarma"). */
    @Volatile
    var onRingingChanged: (() -> Unit)? = null

    /** Lo que se puede decir mientras suena, sin el nombre (lo oye el motor de voz, sin internet). */
    val VOICE_PHRASES = listOf(
        "apagá la alarma", "apagala", "apagá", "pará la alarma", "pará", "basta", "detené la alarma",
        "posponé la alarma", "posponela", "posponé", "cinco minutos más", "diez minutos más",
    )

    /** Si lo dicho pide posponer (y no apagar). */
    fun isSnooze(phrase: String): Boolean = phrase.contains("pospon") || phrase.contains("minutos")

    fun schedule(context: Context, triggerAt: Long, label: String, timer: Boolean): Alarm {
        val prefs = prefs(context)
        val id = prefs.getInt(KEY_NEXT_ID, 1)
        val alarm = Alarm(id, triggerAt, label, timer)
        save(context, list(context) + alarm)
        prefs.edit().putInt(KEY_NEXT_ID, id + 1).apply()
        arm(context, alarm)
        AgentLog.add("ALARMA propia #$id ${if (timer) "temporizador" else "alarma"} para ${java.util.Date(triggerAt)}")
        return alarm
    }

    /** Las que faltan sonar, de la más cercana a la más lejana. */
    fun list(context: Context): List<Alarm> =
        prefs(context).getStringSet(KEY_ALARMS, emptySet()).orEmpty()
            .mapNotNull(::decode)
            .sortedBy { it.triggerAt }

    /** Cancela todas las propias que faltan. Devuelve cuántas eran. */
    fun cancelAll(context: Context): Int {
        val all = list(context)
        all.forEach { alarmManager(context).cancel(firePending(context, it.id)) }
        save(context, emptyList())
        return all.size
    }

    /** Después de reiniciar el teléfono, Android olvida las alarmas: se vuelven a programar. */
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        val (future, past) = list(context).partition { it.triggerAt > now - LATE_MS }
        save(context, future)
        future.forEach { arm(context, it) }
        if (past.isNotEmpty()) AgentLog.add("ALARMA ${past.size} vencidas mientras el teléfono estaba apagado")
    }

    /** Llegó la hora: suena (lo llama [AlarmReceiver]). El permiso de notificaciones se mira antes de avisar. */
    @SuppressLint("MissingPermission")
    fun ring(context: Context, id: Int) {
        val alarm = list(context).firstOrNull { it.id == id } ?: Alarm(id, System.currentTimeMillis(), "", false)
        save(context, list(context).filterNot { it.id == id })
        _ringing.value = alarm
        onRingingChanged?.invoke()
        AgentLog.add("ALARMA sonando #$id")
        val full = PendingIntent.getActivity(
            context, id,
            Intent(context, AlarmRingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when {
            alarm.timer -> "Terminó el temporizador"
            alarm.label.isNotBlank() -> alarm.label
            else -> "Alarma"
        }
        val notification = NotificationCompat.Builder(context, XiaApplication.CHANNEL_ALARMS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(if (alarm.timer && alarm.label.isNotBlank()) alarm.label else "Decí «apagá la alarma» o tocá Detener")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setOngoing(true)
            .setAutoCancel(false)
            // Que no suene para siempre si nadie la apaga.
            .setTimeoutAfter(RING_MAX_MS)
            .addAction(0, "Posponer 10 min", actionPending(context, AlarmReceiver.ACTION_SNOOZE))
            .addAction(0, "Detener", actionPending(context, AlarmReceiver.ACTION_STOP))
            .build()
        // El sonido se repite hasta que se apaga (como un despertador).
        notification.flags = notification.flags or android.app.Notification.FLAG_INSISTENT
        val notified = PermissionManager.hasPostNotifications(context) &&
            runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }.isSuccess
        // Sin permiso de notificaciones o con el canal "Alarmas" silenciado, la
        // notificación no suena: el sonido lo pone la app (como el Reloj).
        if (!notified || !channelRings(context)) playSound(context)
        // Que no quede sonando para siempre si nadie la apaga.
        scheduleTimeout(context, alarm.id)
        // Si Android no deja la pantalla completa (Android 14 a veces), igual se intenta abrirla.
        runCatching { context.startActivity(Intent(context, AlarmRingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Apaga la que suena. false si no había ninguna propia sonando. */
    fun stop(context: Context): Boolean {
        val was = _ringing.value != null
        _ringing.value = null
        stopSound()
        alarmManager(context).cancel(timeoutPending(context, 0))
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        if (was) {
            AgentLog.add("ALARMA detenida")
            onRingingChanged?.invoke()
        }
        return was
    }

    /** La pospone [minutes] minutos. false si no había ninguna propia sonando. */
    fun snooze(context: Context, minutes: Int = SNOOZE_MIN): Boolean {
        val alarm = _ringing.value ?: return false
        stop(context)
        schedule(context, System.currentTimeMillis() + minutes * 60_000L, alarm.label, alarm.timer)
        AgentLog.add("ALARMA pospuesta $minutes min")
        return true
    }

    private fun arm(context: Context, alarm: Alarm) {
        val manager = alarmManager(context)
        val fire = firePending(context, alarm.id)
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        runCatching {
            if (exact) {
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(alarm.triggerAt, show), fire)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.triggerAt, fire)
            }
        }.onFailure {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.triggerAt, fire)
        }
    }

    /** Pasaron [RING_MAX_MS] sonando sin que nadie la apague: se apaga sola (lo llama [AlarmReceiver]). */
    fun timedOut(context: Context, id: Int) {
        if (_ringing.value?.id != id) return
        AgentLog.add("ALARMA #$id se apagó sola después de ${RING_MAX_MS / 60_000} minutos")
        stop(context)
    }

    private fun scheduleTimeout(context: Context, id: Int) {
        runCatching {
            alarmManager(context).setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + RING_MAX_MS, timeoutPending(context, id),
            )
        }
    }

    // Un solo aviso de "se pasó el tiempo" a la vez: el id va en el extra.
    private fun timeoutPending(context: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, TIMEOUT_REQUEST,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_TIMEOUT).putExtra(AlarmReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Si el canal de alarmas va a sonar (no lo silenciaron en los ajustes del teléfono). */
    private fun channelRings(context: Context): Boolean {
        val channel = context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(XiaApplication.CHANNEL_ALARMS) ?: return false
        return channel.importance >= NotificationManager.IMPORTANCE_DEFAULT && channel.sound != null
    }

    private var ringtone: Ringtone? = null

    private fun playSound(context: Context) {
        stopSound()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
        ringtone = runCatching {
            RingtoneManager.getRingtone(context.applicationContext, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        }.getOrNull()
        AgentLog.add("ALARMA suena desde la app (la notificación no podía sonar)")
    }

    private fun stopSound() {
        runCatching { ringtone?.stop() }
        ringtone = null
    }

    private fun firePending(context: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, id,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE).putExtra(AlarmReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun actionPending(context: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun alarmManager(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("own_alarms", Context.MODE_PRIVATE)

    private fun save(context: Context, alarms: List<Alarm>) {
        prefs(context).edit().putStringSet(KEY_ALARMS, alarms.map(::encode).toSet()).apply()
    }

    private fun encode(a: Alarm) = "${a.id}|${a.triggerAt}|${if (a.timer) 1 else 0}|${a.label}"

    private fun decode(raw: String): Alarm? {
        val parts = raw.split('|', limit = 4)
        if (parts.size < 4) return null
        return Alarm(parts[0].toIntOrNull() ?: return null, parts[1].toLongOrNull() ?: return null, parts[3], parts[2] == "1")
    }

    private const val KEY_ALARMS = "alarms"
    private const val KEY_NEXT_ID = "next_id"
    private const val NOTIFICATION_ID = 1010
    private const val SNOOZE_MIN = 10
    private const val RING_MAX_MS = 10 * 60_000L
    private const val TIMEOUT_REQUEST = 7_001
    /** Las que vencieron hace menos de esto mientras el teléfono estaba apagado, igual suenan. */
    private const val LATE_MS = 5 * 60_000L
}
