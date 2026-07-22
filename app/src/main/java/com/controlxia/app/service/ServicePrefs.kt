package com.controlxia.app.service

import android.content.Context

/**
 * Preferencia simple que recuerda si el usuario dejó el servicio encendido,
 * para que BootReceiver y el watchdog sepan si deben relanzarlo.
 */
object ServicePrefs {

    private const val FILE = "service_prefs"
    private const val KEY_ENABLED = "service_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
