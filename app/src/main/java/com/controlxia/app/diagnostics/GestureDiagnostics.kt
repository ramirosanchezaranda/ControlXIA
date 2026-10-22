package com.controlxia.app.diagnostics

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.controlxia.app.BuildConfig
import com.controlxia.app.permissions.PermissionManager
import com.controlxia.app.service.HandsWatchService
import com.controlxia.app.service.WakeWordService
import com.controlxia.app.service.WaveGestures
import com.controlxia.app.service.XiaAccessibilityService
import com.controlxia.app.voice.GestureModel
import com.controlxia.app.voice.WakeWordSettings

/**
 * Un resumen para entender por qué un gesto no anda en un teléfono: qué está
 * activado, qué servicios corren, qué sensor hay y lo último que pasó. Se
 * copia desde Ajustes y se puede pegar en un chat. No incluye mensajes ni
 * pedidos: solo lo de gestos.
 */
object GestureDiagnostics {

    fun report(context: Context): String {
        val settings = WakeWordSettings(context)
        val power = context.getSystemService(PowerManager::class.java)
        val live = HandsWatchService.live.value
        val now = SystemClock.elapsedRealtime()
        val lines = buildList {
            add("ControlXIA ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            add("Escucha: ${on(WakeWordService.running)} · Batería sin restricción: ${yes(PermissionManager.isIgnoringBatteryOptimizations(context))} · Ahorro de energía: ${yes(power?.isPowerSaveMode == true)}")
            add("Control de apps: activado ${yes(XiaAccessibilityService.isEnabled(context))}, conectado ${yes(XiaAccessibilityService.instance != null)} · App adelante: ${XiaAccessibilityService.foregroundPackage ?: "—"}")
            add("Señas con la cámara: ${on(settings.handsPause)} · servicio ${on(HandsWatchService.running)} · permiso de cámara ${yes(PermissionManager.hasCamera(context))}")
            add("Modelos: gestos ${yes(GestureModel.isInstalled(context))}, cara ${yes(GestureModel.isFaceInstalled(context))} · Señas con una mano ${on(settings.handGestures)} · Con cualquier app ${on(settings.handsAlways)} · Sí/no con gestos ${on(settings.confirmGestures)}")
            add("Cámara: ${live.reason.ifBlank { "—" }}")
            add("Última seña: ${live.seen?.let { "$it hace ${(now - live.seenAt) / 1000} s" } ?: "ninguna"}")
            add("Saludar al teléfono: ${on(settings.waveGestures)} · sensor ${WaveGestures.describe(context)}")
            add("Sensor: ${WaveGestures.events} avisos${if (WaveGestures.lastEventAt > 0) ", el último hace ${(now - WaveGestures.lastEventAt) / 1000} s" else ""}")
            add("")
            add("Registro (gestos):")
            AgentLog.recent(150)
                .filter { e -> listOf("MANOS", "SALUDOS", "GESTO").any { e.text.startsWith(it) } }
                .takeLast(40)
                .forEach { add("- ${ago(it.time)} ${it.text}") }
        }
        return lines.joinToString("\n")
    }

    private fun ago(time: Long): String {
        val s = ((System.currentTimeMillis() - time) / 1000).coerceAtLeast(0)
        return if (s < 60) "hace ${s}s" else "hace ${s / 60}m"
    }

    private fun on(value: Boolean) = if (value) "prendido" else "apagado"

    private fun yes(value: Boolean) = if (value) "sí" else "no"
}
