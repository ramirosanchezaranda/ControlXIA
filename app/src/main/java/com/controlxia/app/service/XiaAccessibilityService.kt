package com.controlxia.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Servicio de accesibilidad: le da al asistente "ojos y manos" dentro de las
 * otras apps (leer la pantalla, tocar, escribir, desplazar). No hace nada por
 * su cuenta: solo lo usan las herramientas de pantalla cuando el usuario pide
 * algo por voz (ver actions/ScreenControl).
 *
 * El usuario lo activa en Ajustes → Accesibilidad. En Android 13+, para apps
 * instaladas desde un archivo, antes hay que permitir los "ajustes
 * restringidos" desde la info de la app.
 */
class XiaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        /** El servicio conectado, o null si el usuario no lo activó. */
        @Volatile
        var instance: XiaAccessibilityService? = null
            private set

        /** true si está activado en los ajustes del sistema (aunque aún no conectó). */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = ComponentName(context, XiaAccessibilityService::class.java)
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it) == me
            }
        }
    }
}
