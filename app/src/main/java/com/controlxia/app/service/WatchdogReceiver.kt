package com.controlxia.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Watchdog: alarma inexacta cada ~15 minutos que verifica que el servicio siga
 * vivo y lo relanza si el sistema lo mató. Cada disparo re-agenda el siguiente.
 *
 * Nota Android 12+: iniciar un foreground service desde background está
 * restringido, pero la app pide la exención de optimización de batería en el
 * onboarding, que es una de las exenciones documentadas — sin ella el watchdog
 * puede fallar, y por eso el wizard la marca como obligatoria.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!ServicePrefs.isEnabled(context)) return
        if (!WakeWordService.running) {
            WakeWordService.start(context)
        }
        schedule(context)
    }

    companion object {
        private const val REQUEST_CODE = 2001
        private const val INTERVAL_MS = 15L * 60 * 1000

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, REQUEST_CODE,
                Intent(context, WatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            // setAndAllowWhileIdle funciona también durante Doze (con la
            // granularidad que el sistema permita) y no requiere el permiso
            // de alarmas exactas.
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + INTERVAL_MS,
                pendingIntent(context)
            )
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java)
                .cancel(pendingIntent(context))
        }
    }
}
