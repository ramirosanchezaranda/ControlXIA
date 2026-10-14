package com.controlxia.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Relanza el servicio de escucha cuando el teléfono se reinicia o la app se
 * actualiza, si el usuario lo había dejado encendido.
 *
 * Android 15 prohíbe arrancar un servicio de micrófono desde BOOT_COMPLETED
 * (lanza ForegroundServiceStartNotAllowedException y la app crashea). Ahí se
 * muestra el aviso para reactivar con un toque. En versiones anteriores se
 * intenta arrancar y, si el sistema lo bloquea, se cae al mismo aviso.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        // Android olvida las alarmas al reiniciar: las propias se vuelven a programar.
        com.controlxia.app.actions.OwnAlarms.rescheduleAll(context)
        if (!ServicePrefs.isEnabled(context)) return

        val bootOnAndroid15 = action == Intent.ACTION_BOOT_COMPLETED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
        if (bootOnAndroid15 || !WakeWordService.tryStart(context)) {
            ReactivationNotifier.show(context)
        }
    }
}
