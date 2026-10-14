package com.controlxia.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

class XiaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        com.controlxia.app.ui.theme.ThemeSettings.init(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                getString(R.string.notif_channel_service),
                // IMPORTANCE_LOW: sin sonido, pero visible. La notificación persistente
                // es obligatoria para un foreground service.
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                getString(R.string.notif_channel_alerts),
                // Avisos que requieren una acción del usuario (ej. reactivar la
                // escucha cuando Android no dejó arrancarla en segundo plano).
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        // Alarmas y temporizadores propios: suenan con el sonido de alarma del
        // sistema (por el volumen de alarma, aunque el teléfono esté en silencio).
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARMS, "Alarmas", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alarmas y temporizadores que pusiste por voz"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
                setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }

    companion object {
        const val CHANNEL_SERVICE = "xia_service"
        const val CHANNEL_ALERTS = "xia_alerts"
        const val CHANNEL_ALARMS = "xia_alarms"
    }
}
