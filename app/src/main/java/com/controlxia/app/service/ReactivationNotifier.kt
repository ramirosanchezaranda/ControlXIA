package com.controlxia.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.controlxia.app.R
import com.controlxia.app.XiaApplication
import com.controlxia.app.permissions.PermissionManager
import com.controlxia.app.ui.MainActivity

/**
 * Aviso "tocá para reactivar la escucha". Se usa cuando Android no deja
 * encender el micrófono desde segundo plano:
 *  - Android 15+: prohíbe arrancar un servicio de micrófono desde BOOT_COMPLETED.
 *  - Android 14+: sin la app visible no hay acceso "while-in-use" al micrófono.
 *  - Android 12+: arranques de foreground service desde segundo plano bloqueados.
 *
 * Tocar el aviso abre la app; MainActivity ve que la escucha quedó encendida
 * pero sin correr y la relanza desde primer plano, donde sí está permitido.
 */
object ReactivationNotifier {

    private const val NOTIFICATION_ID = 1002

    @SuppressLint("MissingPermission")
    fun show(context: Context) {
        if (!PermissionManager.hasPostNotifications(context)) return
        val open = PendingIntent.getActivity(
            context, 3,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, XiaApplication.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_reactivate_title))
            .setContentText(context.getString(R.string.notif_reactivate_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.notif_reactivate_text))
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    fun dismiss(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
