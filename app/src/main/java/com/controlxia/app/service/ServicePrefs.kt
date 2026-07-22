package com.controlxia.app.service

import android.content.Context

/**
 * Preferencia simple que recuerda si el usuario dejó el servicio encendido,
 * para que BootReceiver y el watchdog sepan si deben relanzarlo.
 */
object ServicePrefs {

    private const val FILE = "service_prefs"
    private const val KEY_ENABLED = "service_enabled"
    private const val KEY_ONBOARDED = "onboarded"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** true cuando el usuario ya pasó por la configuración inicial. */
    fun isOnboarded(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDED, false)

    fun setOnboarded(context: Context, done: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ONBOARDED, done).apply()
    }
}
