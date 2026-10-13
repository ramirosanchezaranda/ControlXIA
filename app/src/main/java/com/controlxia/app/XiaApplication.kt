package com.controlxia.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class XiaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
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
    }

    companion object {
        const val CHANNEL_SERVICE = "xia_service"
        const val CHANNEL_ALERTS = "xia_alerts"
    }
}
