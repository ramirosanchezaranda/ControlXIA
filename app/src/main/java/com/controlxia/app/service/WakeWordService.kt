package com.controlxia.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.controlxia.app.R
import com.controlxia.app.XiaApplication
import com.controlxia.app.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Servicio de escucha persistente. Corre como Foreground Service de tipo
 * "microphone" con notificación fija, la única categoría a la que Android le
 * permite usar el micrófono de forma continua.
 *
 * Confiabilidad:
 *  - START_STICKY: si el sistema mata el proceso, lo re-crea.
 *  - Watchdog con AlarmManager (~15 min): revive el servicio si murió.
 *  - BootReceiver lo relanza tras cada reinicio del teléfono.
 *
 * Restricción de Android 11+: un servicio iniciado desde background no puede
 * acceder al micrófono. Por eso el servicio se enciende desde la app visible
 * (o desde BOOT_COMPLETED, que está exento) y de ahí en más sobrevive solo.
 *
 * En esta fase todavía no hay wake word real: el hook startListening() queda
 * listo para enchufar Porcupine. La acción "Probar voz" de la notificación
 * demuestra que la app responde por audio incluso con la pantalla bloqueada.
 */
class WakeWordService : Service() {

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("es", "AR")
                ttsReady = true
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        running = true
        ServicePrefs.setEnabled(this, true)
        WatchdogReceiver.schedule(this)

        when (intent?.action) {
            ACTION_SPEAK_TIME -> speakTime()
            ACTION_STOP -> {
                stopFromUser()
                return START_NOT_STICKY
            }
            else -> startListening()
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        // FOREGROUND_SERVICE_TYPE_MICROPHONE existe desde API 30; en versiones
        // anteriores alcanza con el tipo declarado en el manifest.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val speakTime = PendingIntent.getService(
            this, 1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_SPEAK_TIME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, XiaApplication.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_listening_title))
            .setContentText(getString(R.string.notif_listening_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.notif_action_test), speakTime)
            .addAction(0, getString(R.string.notif_action_stop), stop)
            .build()
    }

    /**
     * Hook para la detección de wake word (Porcupine) — próxima fase.
     * Acá se inicializará el motor de wake word con el micrófono.
     */
    private fun startListening() {
        // TODO(fase-wake-word): inicializar Porcupine y escuchar "Xia".
    }

    /**
     * Prueba de vida: responde la hora por TTS. Funciona con pantalla bloqueada.
     * El wake lock parcial garantiza que la CPU no se duerma a mitad de la frase
     * si el teléfono está en Doze.
     */
    private fun speakTime() {
        if (!ttsReady) return
        // Wake lock con timeout: alcanza para decir la frase y se libera solo.
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ControlXIA:speak")
            .acquire(10_000L)
        val hora = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        tts?.speak("Son las $hora", TextToSpeech.QUEUE_FLUSH, null, "speak_time")
    }

    private fun stopFromUser() {
        ServicePrefs.setEnabled(this, false)
        WatchdogReceiver.cancel(this)
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_SPEAK_TIME = "com.controlxia.app.action.SPEAK_TIME"
        const val ACTION_STOP = "com.controlxia.app.action.STOP"

        /** Estado observable simple para la UI. */
        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, WakeWordService::class.java)
            )
        }

        fun stop(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WakeWordService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
