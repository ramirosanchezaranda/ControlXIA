package com.controlxia.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.controlxia.app.voice.WakeWordSettings

/**
 * Cuándo conviene tener el micrófono abierto esperando el nombre, para cuidar
 * la batería. Con el micrófono abierto el teléfono no puede dormir del todo;
 * así que, con la pantalla apagada, se pausa si la batería está baja o en modo
 * ahorro (o siempre, si el usuario lo pide). Con la pantalla prendida o
 * cargando, escucha siempre.
 *
 * Avisa con [onChange] cuando cambia algo (pantalla, cargador, batería baja,
 * modo ahorro). Solo escucha avisos que Android manda pocas veces al día, no
 * cada cambio de batería.
 */
class ListeningPolicy(private val context: Context, private val onChange: () -> Unit) {

    /** Por qué no escuchar ahora (para mostrarlo), o null si hay que escuchar. */
    fun pauseReason(): String? {
        val settings = WakeWordSettings(context)
        return pauseReason(
            screenOn = power.isInteractive,
            charging = isCharging(),
            lowBattery = isLow(),
            powerSave = power.isPowerSaveMode,
            listenScreenOff = settings.listenScreenOff,
            pauseOnLowBattery = settings.pauseOnLowBattery,
        )
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onChange()
    }

    private val power get() = context.getSystemService(PowerManager::class.java)

    /** El último estado de la batería, sin quedarse escuchando cada cambio. */
    private fun battery(): Intent? =
        ContextCompat.registerReceiver(context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)

    private fun isCharging(): Boolean {
        val status = battery()?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: return false
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun isLow(): Boolean {
        val intent = battery() ?: return false
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return level >= 0 && level * 100 / scale.coerceAtLeast(1) <= LOW_PERCENT
    }

    companion object {
        const val LOW_PERCENT = 15

        /** La regla, sin Android (para probarla). */
        fun pauseReason(
            screenOn: Boolean,
            charging: Boolean,
            lowBattery: Boolean,
            powerSave: Boolean,
            listenScreenOff: Boolean,
            pauseOnLowBattery: Boolean,
        ): String? = when {
            screenOn || charging -> null
            !listenScreenOff -> "En pausa con la pantalla apagada"
            pauseOnLowBattery && lowBattery -> "En pausa por batería baja"
            pauseOnLowBattery && powerSave -> "En pausa por el ahorro de batería"
            else -> null
        }
    }
}
