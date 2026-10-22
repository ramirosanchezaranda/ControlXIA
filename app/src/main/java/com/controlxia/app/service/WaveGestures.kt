package com.controlxia.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.controlxia.app.actions.DeviceControl
import com.controlxia.app.actions.MediaApps
import com.controlxia.app.actions.OwnAlarms
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.ui.ScreenOnActivity
import com.controlxia.app.voice.Feedback
import com.controlxia.app.voice.WaveDetector

/**
 * Gestos con la mano encima del teléfono, con el sensor de proximidad (anda
 * con la pantalla apagada y casi no gasta):
 *
 * - 👋 un saludo con la pantalla apagada: la prende.
 * - 👋👋 dos saludos seguidos: te escucha (como decir el nombre).
 * - ✋ tapar un segundo: pospone la alarma que suena, frena al asistente, o
 *   pausa el video que estás mirando.
 *
 * Durante una llamada no hace nada (ahí el sensor es para la oreja). Si la
 * prendió y enseguida queda tapado (estaba en el bolsillo), la vuelve a apagar.
 */
class WaveGestures(
    context: Context,
    /** Escuchar un pedido ya. */
    private val onListen: () -> Unit,
    /** Frenar lo que está haciendo el asistente; false si no estaba haciendo nada. */
    private val onStop: () -> Boolean,
) : SensorEventListener {

    private val context = context.applicationContext
    private val sensors = this.context.getSystemService(SensorManager::class.java)
    private val power = this.context.getSystemService(PowerManager::class.java)
    private val audio = this.context.getSystemService(AudioManager::class.java)
    // El que despierta al teléfono, si existe: con la pantalla apagada es el único que avisa seguro.
    private val sensor: Sensor? = sensors?.let { it.getDefaultSensor(Sensor.TYPE_PROXIMITY, true) ?: it.getDefaultSensor(Sensor.TYPE_PROXIMITY) }
    private var offEvents = 0
    private val detector = WaveDetector()
    private val feedback = Feedback(this.context)
    private val handler = Handler(Looper.getMainLooper())
    private val checkCover = Runnable { detector.checkCover(SystemClock.elapsedRealtime())?.let { handle(it) } }
    private var listening = false
    private var wokeAt = Long.MIN_VALUE / 2

    @Suppress("DEPRECATION") // Sigue siendo la forma de prender la pantalla desde un servicio.
    private val screenLock: PowerManager.WakeLock? by lazy {
        power?.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "ControlXIA:wave",
        )?.apply { setReferenceCounted(false) }
    }

    fun start() {
        if (listening || sensor == null) return
        detector.reset()
        listening = sensors?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler) == true
        if (listening) {
            AgentLog.add("SALUDOS escuchando el sensor «${sensor.name}» (${sensor.vendor}, despierta: ${if (sensor.isWakeUpSensor) "sí" else "no"})")
        }
    }

    fun stop() {
        if (!listening) return
        sensors?.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        listening = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        events++
        lastEventAt = now
        // Para el diagnóstico: si el sensor avisa con la pantalla apagada (en algunos teléfonos no).
        if (power?.isInteractive == false) {
            if (offEvents++ == 0) AgentLog.add("SALUDOS el sensor avisa con la pantalla apagada")
        } else {
            offEvents = 0
        }
        val result = detector.feed(isNear(event), now)
        if (detector.isNear) {
            handler.removeCallbacks(checkCover)
            handler.postDelayed(checkCover, detector.coverMs)
        }
        result?.let { handle(it) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun handle(event: WaveDetector.Event) {
        // En una llamada el sensor es para la oreja.
        if (inCall()) return
        when (event) {
            is WaveDetector.Event.Wave -> onWave(event.count)
            WaveDetector.Event.Cover -> onCover()
        }
    }

    private fun onWave(count: Int) {
        if (count >= 2) {
            AgentLog.add("SALUDOS dos saludos: te escucho")
            if (!power.isOn()) wake()
            onListen()
        } else if (!power.isOn()) {
            AgentLog.add("SALUDOS saludo: prendo la pantalla")
            wake()
        }
    }

    private fun onCover() {
        val now = SystemClock.elapsedRealtime()
        when {
            OwnAlarms.ringing.value != null -> {
                AgentLog.add("SALUDOS tapaste el teléfono: pospongo la alarma")
                feedback.vibrate(40)
                OwnAlarms.snooze(context)
            }
            onStop() -> feedback.vibrate(40)
            // La prendió un saludo y quedó tapada enseguida: si sigue tapada un
            // rato más, estaba en el bolsillo (o boca abajo) y se vuelve a apagar.
            now - wokeAt < POCKET_GUARD_MS && power.isOn() -> handler.postDelayed({
                if (detector.isNear && power.isOn() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    AgentLog.add("SALUDOS quedó tapada apenas la prendí (¿bolsillo?): la apago")
                    XiaAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                }
            }, POCKET_CONFIRM_MS)
            // En horizontal la mano que sostiene el teléfono tapa el sensor sin querer.
            power.isOn() && playingVideo() && !landscape() -> {
                AgentLog.add("SALUDOS tapaste el teléfono: pauso el video")
                feedback.vibrate(40)
                DeviceControl(context).media(DeviceControl.Media.PAUSE)
            }
        }
    }

    /** Prende la pantalla (queda en la pantalla de bloqueo, o en la app si no estaba bloqueado). */
    private fun wake() {
        wokeAt = SystemClock.elapsedRealtime()
        runCatching { screenLock?.acquire(SCREEN_HOLD_MS) }
        // Si el fabricante no deja prender la pantalla así, con una pantalla invisible.
        handler.postDelayed({
            if (!power.isOn()) {
                runCatching {
                    context.startActivity(
                        Intent(context, ScreenOnActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }, FALLBACK_AFTER_MS)
    }

    private fun inCall(): Boolean = audio?.mode.let {
        it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_IN_COMMUNICATION || it == AudioManager.MODE_RINGTONE
    }

    private fun playingVideo(): Boolean =
        XiaAccessibilityService.foregroundPackage in MediaApps.VIDEO &&
            runCatching { audio?.isMusicActive == true }.getOrDefault(false)

    private fun PowerManager?.isOn(): Boolean = this?.isInteractive == true

    private fun landscape(): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    companion object {
        /** Más cerca que esto es "tapado" en los sensores que miden distancia. */
        private const val NEAR_CM = 5f
        private const val SCREEN_HOLD_MS = 3_000L
        private const val FALLBACK_AFTER_MS = 600L
        private const val POCKET_GUARD_MS = 4_000L
        private const val POCKET_CONFIRM_MS = 1_500L

        /** Muchos sensores solo dicen 0 (cerca) o su máximo (lejos); otros, centímetros. */
        fun isNear(event: SensorEvent): Boolean {
            val distance = event.values.firstOrNull() ?: return false
            return distance < minOf(event.sensor.maximumRange, NEAR_CM)
        }

        /**
         * Si el sensor despierta al teléfono con la pantalla apagada. Los
         * "virtuales" (algunos Xiaomi, OPPO, Samsung) no: ahí el saludo con la
         * pantalla apagada puede no andar.
         */
        fun wakesUp(context: Context): Boolean =
            context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.isWakeUpSensor == true

        /** Para el diagnóstico: cuántas veces avisó el sensor y cuándo fue la última. */
        @Volatile
        var events = 0
            private set

        @Volatile
        var lastEventAt = 0L
            private set

        /** El sensor que se usa, para el diagnóstico. */
        fun describe(context: Context): String {
            val sensors = context.getSystemService(SensorManager::class.java) ?: return "sin servicio de sensores"
            val wake = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY, true)
            val any = wake ?: sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY) ?: return "no hay sensor de proximidad"
            return "«${any.name}» de ${any.vendor}, despierta al teléfono: ${if (any.isWakeUpSensor) "sí" else "no"}, " +
                "alcance ${any.maximumRange} cm"
        }

        /** Si el teléfono tiene sensor de proximidad. */
        fun available(context: Context): Boolean =
            context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null
    }
}
