package com.controlxia.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Relanza el servicio de escucha cuando el teléfono se reinicia o la app se
 * actualiza, si el usuario lo había dejado encendido. BOOT_COMPLETED es una de
 * las exenciones que permiten iniciar un foreground service desde background.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        if (ServicePrefs.isEnabled(context)) {
            WakeWordService.start(context)
        }
    }
}
