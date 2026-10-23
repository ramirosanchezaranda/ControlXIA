package com.controlxia.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.controlxia.app.actions.ScreenNumbers
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.permissions.A11yHealth

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

    /** Ventanas que no son "la app de adelante": el teclado, la barra del sistema, la burbuja propia. */
    private var notApps: Set<String> = emptySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val keyboards = runCatching {
            getSystemService(InputMethodManager::class.java)?.enabledInputMethodList.orEmpty().map { it.packageName }
        }.getOrDefault(emptyList())
        notApps = setOf(packageName, "com.android.systemui") + keyboards
        A11yHealth.onConnected(this)
        AgentLog.add("ACCESIBILIDAD conectada")
    }

    /**
     * Con los números a la vista, se actualizan cuando cambia la pantalla.
     * Nada de acá puede tirar el servicio: si se cae, Android lo deja
     * "activado" pero sin andar hasta que se apague y se prenda a mano.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        runCatching {
            // Qué app está adelante, sin tener que leer la pantalla (lo usa la cámara de los gestos).
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val pkg = event.packageName?.toString()
                if (pkg != null && pkg != foregroundPackage && pkg !in notApps) {
                    foregroundPackage = pkg
                    foregroundListeners.forEach { listener -> runCatching { listener(pkg) } }
                }
            }
            if (event.packageName == packageName) return
            ScreenNumbers.onScreenChanged()
        }.onFailure { AgentLog.add("ACCESIBILIDAD error con un evento: ${it.message}") }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        runCatching { ScreenNumbers.hide() }
        disconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnected()
        super.onDestroy()
    }

    private fun disconnected() {
        if (instance !== this) return
        instance = null
        A11yHealth.onDisconnected()
        AgentLog.add("ACCESIBILIDAD desconectada")
    }

    companion object {
        /** El servicio conectado, o null si el usuario no lo activó. */
        @Volatile
        var instance: XiaAccessibilityService? = null
            private set

        /** La app que está adelante (según el último cambio de ventana). */
        @Volatile
        var foregroundPackage: String? = null
            private set

        /** Quiénes quieren saber cuando cambia la app de adelante. */
        val foregroundListeners = java.util.concurrent.CopyOnWriteArraySet<(String) -> Unit>()

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
