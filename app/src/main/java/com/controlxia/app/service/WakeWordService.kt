package com.controlxia.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.controlxia.app.R
import com.controlxia.app.XiaApplication
import com.controlxia.app.permissions.PermissionManager
import com.controlxia.app.ui.MainActivity
import com.controlxia.app.voice.AndroidSpeechToText
import com.controlxia.app.voice.PorcupineWakeWordEngine
import com.controlxia.app.voice.RecentCommandsStore
import com.controlxia.app.voice.SpeechToText
import com.controlxia.app.voice.WakeWordEngine
import com.controlxia.app.voice.WakeWordSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Servicio de escucha persistente. Corre como Foreground Service de tipo
 * "microphone" con notificación fija, la única categoría a la que Android le
 * permite usar el micrófono de forma continua.
 *
 * Confiabilidad: START_STICKY + watchdog (AlarmManager) + BootReceiver.
 * Restricción de Android 11+: el servicio se enciende desde la app visible o
 * desde BOOT_COMPLETED, y de ahí en más sobrevive solo.
 *
 * Cadena de voz (esta fase): wake word (Porcupine) → confirmación (vibración +
 * TTS) → transcripción del comando (SpeechRecognizer). Todavía no se ejecuta la
 * acción: la transcripción queda guardada y visible. Porcupine y SpeechRecognizer
 * comparten el micrófono, así que se para el wake word mientras se transcribe y
 * se reanuda al terminar.
 */
class WakeWordService : Service() {

    private val main = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var wakeEngine: WakeWordEngine? = null
    private var stt: SpeechToText? = null
    private var listeningCommand = false
    private var notifText: String = ""

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
        if (notifText.isBlank()) notifText = getString(R.string.notif_listening_text)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(notifText), type)
    }

    private fun buildNotification(contentText: String): Notification {
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
            .setContentText(contentText)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.notif_action_test), speakTime)
            .addAction(0, getString(R.string.notif_action_stop), stop)
            .build()
    }

    private fun updateNotification(text: String) {
        notifText = text
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionManager.hasPostNotifications(this)
        ) return
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
    }

    /**
     * Inicia el motor de wake word si están dadas las condiciones (permiso de
     * micrófono + AccessKey configurado). Idempotente: si ya está escuchando,
     * no hace nada. Degrada con gracia: sin AccessKey, el servicio sigue vivo
     * y el resto de la app funciona; solo no hay palabra de activación.
     */
    private fun startListening() {
        if (wakeEngine != null) return
        if (!PermissionManager.hasRecordAudio(this)) {
            wakeWordActive = false
            wakeWordStatus = "Falta el permiso de micrófono"
            return
        }
        val settings = WakeWordSettings(this)
        if (!settings.isReady()) {
            wakeWordActive = false
            wakeWordStatus = "Falta el AccessKey de Picovoice"
            return
        }
        try {
            val engine = PorcupineWakeWordEngine(
                context = applicationContext,
                accessKey = settings.accessKey,
                keyword = settings.keyword,
                sensitivity = settings.sensitivity,
            )
            engine.start { onWakeWordDetected() }
            wakeEngine = engine
            wakeWordActive = true
            wakeWordStatus = "Escuchando “${settings.keyword.label}”"
            updateNotification(getString(R.string.notif_listening_text))
        } catch (e: Exception) {
            wakeEngine = null
            wakeWordActive = false
            wakeWordStatus = e.message ?: "No se pudo iniciar el wake word"
        }
    }

    /**
     * Se dispara al escuchar la palabra de activación (en el hilo del motor).
     * Confirma con vibración + TTS, para el wake word para liberar el micrófono,
     * transcribe el comando y reanuda la escucha al terminar.
     */
    private fun onWakeWordDetected() {
        main.post {
            if (listeningCommand) return@post
            listeningCommand = true

            acquireBriefWakeLock()
            vibrate()
            if (ttsReady) {
                tts?.speak("Te escucho", TextToSpeech.QUEUE_FLUSH, null, "ack")
            }
            wakeEngine?.stop() // liberar el micrófono para el ASR

            // Pequeña espera para que el "Te escucho" no se transcriba a sí mismo.
            main.postDelayed({ transcribeCommand() }, 900L)
        }
    }

    private fun transcribeCommand() {
        val recognizer = stt ?: AndroidSpeechToText(applicationContext).also { stt = it }
        updateNotification("Escuchando tu comando…")
        recognizer.listen(
            onPartial = { partial -> updateNotification("… $partial") },
            onResult = { text ->
                RecentCommandsStore(applicationContext).add(text)
                lastCommand = text
                updateNotification("Escuché: “$text”")
                if (ttsReady) {
                    tts?.speak("Escuché: $text", TextToSpeech.QUEUE_FLUSH, null, "heard")
                }
                resumeListening()
            },
            onError = { message ->
                updateNotification(getString(R.string.notif_listening_text))
                wakeWordStatus = message
                resumeListening()
            },
        )
    }

    private fun resumeListening() {
        listeningCommand = false
        // Reanudar el wake word; si el engine se perdió, reconstruirlo.
        val engine = wakeEngine
        if (engine != null) {
            runCatching { engine.start { onWakeWordDetected() } }
                .onFailure { startListeningFresh() }
        } else {
            startListeningFresh()
        }
    }

    private fun startListeningFresh() {
        wakeEngine?.release()
        wakeEngine = null
        startListening()
    }

    /**
     * Prueba de vida: responde la hora por TTS. Funciona con pantalla bloqueada.
     */
    private fun speakTime() {
        if (!ttsReady) return
        acquireBriefWakeLock()
        val hora = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        tts?.speak("Son las $hora", TextToSpeech.QUEUE_FLUSH, null, "speak_time")
    }

    private fun acquireBriefWakeLock() {
        // Wake lock con timeout: mantiene la CPU despierta lo justo para la
        // confirmación y la transcripción aunque el teléfono esté en Doze.
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ControlXIA:voice")
            .acquire(15_000L)
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(120)
        }
    }

    private fun stopFromUser() {
        ServicePrefs.setEnabled(this, false)
        WatchdogReceiver.cancel(this)
        releaseVoice()
        running = false
        wakeWordActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseVoice() {
        main.removeCallbacksAndMessages(null)
        listeningCommand = false
        stt?.cancel()
        stt = null
        wakeEngine?.release()
        wakeEngine = null
    }

    override fun onDestroy() {
        running = false
        wakeWordActive = false
        releaseVoice()
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

        /** true cuando el wake word está escuchando activamente. */
        @Volatile
        var wakeWordActive: Boolean = false
            private set

        /** Último estado legible del wake word (motivo si está inactivo). */
        @Volatile
        var wakeWordStatus: String? = null
            private set

        /** Última transcripción escuchada (para feedback inmediato en la UI). */
        @Volatile
        var lastCommand: String? = null
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

        /** Dispara la prueba de voz ("son las HH:mm") sobre el servicio activo. */
        fun speakTime(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WakeWordService::class.java).setAction(ACTION_SPEAK_TIME)
            )
        }
    }
}
