package com.controlxia.app.actions

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.controlxia.app.brain.NotificationSummary
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.permissions.PermissionManager
import com.controlxia.app.service.XiaNotificationListener

/**
 * Las notificaciones que están en la barra ahora: leerlas en voz alta, abrir
 * una o borrarlas. Funciona con el teléfono bloqueado (abrir una pide
 * desbloquear). Se lee lo que se ve en la barra, no se guarda nada.
 */
class NotificationReader(private val context: Context) {

    /** "Tenés 3 notificaciones. WhatsApp: Juan, hola…" ([filter]: app o remitente). */
    fun read(filter: String?): String {
        val items = items() ?: return NO_ACCESS
        AgentLog.add("NOTIFICACIONES ${items.size}${filter?.let { " de «$it»" }.orEmpty()}")
        return NotificationSummary.speak(items.map { it.first }, filter)
    }

    /** Abre la notificación más nueva (de [filter], si se dice), como si la tocaras. */
    fun open(filter: String?): String {
        val items = items() ?: return NO_ACCESS
        val (entry, sbn) = NotificationSummary.pick(items, filter)
            ?: return if (filter.isNullOrBlank()) "No tenés notificaciones." else "No tenés notificaciones de $filter."
        val intent = sbn.notification.contentIntent
            ?: return "Esa notificación de ${entry.app} no se puede abrir."
        if (!PermissionManager.canLaunchActivities(context)) {
            return "Para abrirla necesito el permiso Mostrar sobre otras apps."
        }
        return try {
            intent.send(context, 0, null, null, null, null, backgroundStart())
            if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) {
                runCatching { XiaNotificationListener.instance?.cancelNotification(sbn.key) }
            }
            AgentLog.add("NOTIFICACIONES abrí la de ${entry.app}")
            "Abrí la notificación de ${entry.app}."
        } catch (e: PendingIntent.CanceledException) {
            "Esa notificación ya no se puede abrir."
        }
    }

    /** Borra las que se pueden borrar (las fijas, como la música, quedan). */
    fun clear(): String {
        val listener = XiaNotificationListener.instance ?: return NO_ACCESS
        val count = items()?.size ?: 0
        runCatching { listener.cancelAllNotifications() }
        AgentLog.add("NOTIFICACIONES borradas ($count)")
        return if (count == 0) "No había notificaciones para borrar." else "Listo, borré las notificaciones."
    }

    /** Las notificaciones útiles, de la más nueva a la más vieja. null = sin acceso. */
    private fun items(): List<Pair<NotificationSummary.Entry, StatusBarNotification>>? {
        val listener = XiaNotificationListener.instance ?: return null
        val active = runCatching { listener.activeNotifications }.getOrNull().orEmpty()
        return active
            .filter { useful(it) }
            .sortedByDescending { it.postTime }
            .mapNotNull { sbn -> entry(sbn)?.let { it to sbn } }
    }

    private fun useful(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == context.packageName) return false
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        // Las fijas (música, descargas, servicios) no son avisos para leer.
        if (sbn.isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        return n.category != Notification.CATEGORY_TRANSPORT && n.category != Notification.CATEGORY_PROGRESS
    }

    private fun entry(sbn: StatusBarNotification): NotificationSummary.Entry? {
        val extras = sbn.notification.extras ?: return null
        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        var text = (
            extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
            )?.toString().orEmpty()
        // Chats: el último mensaje y quién lo mandó.
        lastMessage(extras)?.let { (sender, body) ->
            if (sender.isNotBlank() && !title.contains(sender)) title = if (title.isBlank()) sender else "$title, $sender"
            text = body
        }
        if (title.isBlank() && text.isBlank()) return null
        return NotificationSummary.Entry(appName(sbn.packageName), title, text)
    }

    @Suppress("DEPRECATION")
    private fun lastMessage(extras: Bundle): Pair<String, String>? {
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val last = messages.lastOrNull() as? Bundle ?: return null
        val body = last.getCharSequence("text")?.toString() ?: return null
        val sender = last.getCharSequence("sender")?.toString().orEmpty()
        return sender to body
    }

    private fun appName(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg.substringAfterLast('.'))

    /** Android 14+: abrir la app de otro desde segundo plano con nuestro permiso. */
    private fun backgroundStart(): Bundle? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else {
            null
        }

    companion object {
        const val NO_ACCESS = "Para leerte las notificaciones necesito el acceso a notificaciones: activalo en ControlXIA."
    }
}
