package com.controlxia.app.service

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.controlxia.app.actions.MessageStore

/**
 * Escucha las notificaciones para que el asistente pueda leer los mensajes
 * que llegan (WhatsApp, Telegram, SMS, Messenger, Instagram…) y responderlos
 * con la acción "Responder" de la propia notificación, sin abrir la app.
 *
 * Solo guarda los últimos mensajes en memoria, mientras la app está viva; no
 * los escribe en disco. El usuario lo activa en Ajustes → Acceso a
 * notificaciones.
 */
class XiaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        // Mensajes que ya estaban en la barra cuando se conectó.
        runCatching { activeNotifications?.forEach { MessageStore.onPosted(this, it) } }
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        MessageStore.onPosted(this, sbn)
    }

    companion object {
        @Volatile
        var instance: XiaNotificationListener? = null
            private set

        fun isEnabled(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }
}
