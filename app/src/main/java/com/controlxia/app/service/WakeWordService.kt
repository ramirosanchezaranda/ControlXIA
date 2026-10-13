package com.controlxia.app.service

import android.annotation.SuppressLint
import android.app.KeyguardManager
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
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.controlxia.app.R
import com.controlxia.app.XiaApplication
import com.controlxia.app.brain.CommandProcessor
import com.controlxia.app.brain.Reply
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.permissions.PermissionManager
import com.controlxia.app.ui.AssistantActivity
import com.controlxia.app.ui.MainActivity
import com.controlxia.app.voice.AndroidSpeechToText
import com.controlxia.app.voice.AssistantState
import com.controlxia.app.voice.ListeningOverlay
import com.controlxia.app.voice.RecentCommandsStore
import com.controlxia.app.voice.SpeechToText
import com.controlxia.app.voice.VoiceSettings
import com.controlxia.app.voice.VoskModelStore
import com.controlxia.app.voice.VoskWakeWordEngine
import com.controlxia.app.voice.WakeWordEngine
import com.controlxia.app.voice.WakeWordSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Servicio de escucha persistente. Corre como Foreground Service de tipo
 * "microphone" con notificación fija, la única categoría a la que Android le
 * permite usar el micrófono de forma continua.
 *
 * Confiabilidad: START_STICKY + watchdog (AlarmManager) + BootReceiver. Si
 * Android no deja encender el micrófono desde segundo plano (Android 14+), el
 * servicio no crashea: avisa con una notificación para reactivarlo con un toque.
 *
 * Cadena de voz: wake word (Vosk) → "Te escucho" (vibración + TTS) →
 * transcripción (SpeechRecognizer) → cerebro (LLM + tools) → respuesta hablada
 * → vuelve a esperar la palabra. Vosk y SpeechRecognizer comparten el
 * micrófono, así que el wake word se pausa mientras se transcribe. Cada espera
 * tiene un tope de tiempo: si el TTS o el reconocedor no avisan, igual se sigue
 * y la escucha nunca queda colgada.
 */
class WakeWordService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    /** Frase pedida antes de que el TTS termine de inicializar. */
    private var pendingSpeech: Pair<String, String>? = null

    private var wakeEngine: WakeWordEngine? = null
    private var stt: SpeechToText? = null
    private var commandProcessor: CommandProcessor? = null
    private var overlay: ListeningOverlay? = null
    private var inForeground = false
    private var listeningCommand = false
    private var transcriptionStarted = false
    private var reloadPending = false
    private var lockedSession = false
    private var showContentLocked = true
    private var agentName = "Xia"
    private var notifText: String = ""

    /** Wake lock de una interacción (palabra → respuesta), con tope de tiempo. */
    private val voiceWakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ControlXIA:voice")
            .apply { setReferenceCounted(false) }
    }

    private val afterAck = Runnable { startTranscriptionOnce() }
    private val sttTimeout = Runnable { onTranscriptionTimeout() }
    private val resumeAfterReply = Runnable { afterReply() }

    /** El asistente hizo una pregunta: al terminar de hablar, escuchar la respuesta. */
    private var followUpPending = false
    private var followUps = 0
    private val retryWakeWord = Runnable { if (!listeningCommand) startListening() }

    override fun onCreate() {
        super.onCreate()
        overlay = ListeningOverlay(this)
        agentName = WakeWordSettings(this).agentName
        tts = TextToSpeech(this) { status -> main.post { onTtsInit(status) } }
    }

    private fun onTtsInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        applySpanishLanguage(engine)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                main.post { onUtteranceFinished(utteranceId) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { onUtteranceFinished(utteranceId) }
            }
        })
        ttsReady = true
        pendingSpeech?.let { (text, id) ->
            pendingSpeech = null
            speakOut(text, id)
        }
    }

    /** es-AR si está instalado; si no, el español más cercano disponible. */
    private fun applySpanishLanguage(engine: TextToSpeech) {
        val candidates = listOf(
            Locale("es", "AR"), Locale("es", "US"), Locale("es", "ES"), Locale("es"),
        )
        for (locale in candidates) {
            val result = runCatching { engine.setLanguage(locale) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            ) return
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Apagar desde la notificación: no hace falta pasar a primer plano.
        if (intent?.action == ACTION_STOP) {
            stopFromUser()
            return START_NOT_STICKY
        }

        if (!inForeground) {
            inForeground = startAsForeground()
            if (!inForeground) {
                // Android no dejó usar el micrófono desde segundo plano: avisar
                // para reactivar con un toque, sin crashear. La preferencia
                // queda encendida para que la app lo relance al abrirse.
                running = false
                ReactivationNotifier.show(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        running = true
        ServicePrefs.setEnabled(this, true)
        ReactivationNotifier.dismiss(this)
        WatchdogReceiver.schedule(this)

        when (intent?.action) {
            ACTION_RELOAD -> reloadWakeWord()
            ACTION_SPEAK_TIME -> {
                startListening()
                speakTime()
            }
            else -> startListening()
        }
        return START_STICKY
    }

    /**
     * Pasa a primer plano con tipo micrófono. Devuelve false si Android lo
     * rechaza (Android 14+ arrancando desde segundo plano, o sin permiso de
     * micrófono). En ese caso igual cumple el contrato de startForegroundService
     * con un shortService, para que el sistema no mate la app, y el llamador
     * detiene el servicio.
     */
    private fun startAsForeground(): Boolean {
        notifText = idleNotificationText()
        val notification = buildNotification(notifText)
        // FOREGROUND_SERVICE_TYPE_MICROPHONE existe desde API 30; en versiones
        // anteriores alcanza con el tipo declarado en el manifest.
        val micType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, micType)
            true
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching {
                    ServiceCompat.startForeground(
                        this, NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
                    )
                }
            }
            false
        }
    }

    private fun idleNotificationText(): String =
        if (!PermissionManager.hasRecordAudio(this)) {
            "Falta el permiso de micrófono"
        } else {
            "Decí “${WakeWordSettings(this).spokenWakeWord()}” para dar una orden"
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
        val title = if (wakeWordActive) "$agentName está escuchando" else "$agentName en espera"
        return NotificationCompat.Builder(this, XiaApplication.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notif_action_test), speakTime)
            .addAction(0, getString(R.string.notif_action_stop), stop)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(text: String) {
        notifText = text
        if (!inForeground) return
        if (!PermissionManager.hasPostNotifications(this)) return
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun setWakeStatus(active: Boolean, status: String, failed: Boolean = false) {
        wakeWordActive = active
        wakeWordStatus = status
        wakeWordFailed = failed
        updateNotification(if (active) idleNotificationText() else status)
    }

    /**
     * Inicia el wake word (Vosk) si hay permiso de micrófono. Idempotente: si
     * ya hay un motor, no hace nada. El motor arranca en su propio hilo (la
     * primera vez prepara el modelo de voz, unos segundos) y avisa por
     * callbacks cuándo está escuchando o si falló.
     */
    private fun startListening() {
        if (wakeEngine != null || listeningCommand) return
        main.removeCallbacks(retryWakeWord)
        if (!PermissionManager.hasRecordAudio(this)) {
            setWakeStatus(false, "Falta el permiso de micrófono", failed = true)
            return
        }
        val settings = WakeWordSettings(this)
        agentName = settings.agentName
        val engine = VoskWakeWordEngine(
            context = applicationContext,
            phrase = settings.spokenWakeWord(),
            sensitivity = settings.sensitivity,
        )
        wakeEngine = engine
        setWakeStatus(false, "Preparando el reconocimiento de voz…")
        startEngine(engine)
    }

    private fun startEngine(engine: WakeWordEngine) {
        val heard = WakeWordSettings(this).spokenWakeWord()
        engine.start(
            onDetected = { onWakeWordDetected() },
            onError = { message, transient -> onWakeEngineError(engine, message, transient) },
            onReady = {
                main.post {
                    if (wakeEngine === engine && !listeningCommand) {
                        setWakeStatus(true, "Escuchando “$heard”")
                    }
                }
            },
        )
    }

    /**
     * El motor no pudo arrancar o se cayó. Si es algo pasajero (una llamada u
     * otra app tomó el micrófono) se reintenta solo; si es de configuración
     * (palabra fuera del vocabulario) queda el motivo a la vista en el panel.
     */
    private fun onWakeEngineError(engine: WakeWordEngine, message: String, transient: Boolean) {
        main.post {
            if (wakeEngine !== engine || listeningCommand) return@post
            engine.release()
            wakeEngine = null
            setWakeStatus(false, message, failed = true)
            if (transient) {
                main.removeCallbacks(retryWakeWord)
                main.postDelayed(retryWakeWord, ENGINE_RETRY_MS)
            }
        }
    }

    /** Aplica cambios de ajustes del wake word sin apagar el servicio. */
    private fun reloadWakeWord() {
        agentName = WakeWordSettings(this).agentName
        if (listeningCommand) {
            reloadPending = true
            return
        }
        startListeningFresh()
    }

    /**
     * Se dispara al escuchar la palabra de activación (en el hilo del motor).
     * Confirma con vibración + "Te escucho", pausa el wake word para liberar el
     * micrófono y transcribe el comando cuando termina la confirmación.
     */
    private fun onWakeWordDetected() {
        main.post {
            if (listeningCommand) return@post
            listeningCommand = true
            followUps = 0
            followUpPending = false
            transcriptionStarted = false

            voiceWakeLock.acquire(INTERACTION_MAX_MS)
            vibrate()

            val settings = WakeWordSettings(this)
            agentName = settings.agentName
            lockedSession = isLockedOrScreenOff()
            showContentLocked = settings.showContentWhenLocked
            AssistantState.begin(agentName, lockedSession)
            if (lockedSession) {
                // Bloqueado / pantalla apagada: prender la pantalla y responder
                // sobre el bloqueo (requiere permiso de overlay para lanzarla).
                if (overlay?.canShow() == true) launchAssistantScreen()
            } else {
                // Desbloqueado: pill flotante sobre la app actual.
                overlay?.show(agentName, "Escuchando…")
            }
            wakeEngine?.stop() // liberar el micrófono para el ASR

            // Transcribir al terminar el "Te escucho" (así no se transcribe a sí
            // mismo). Tope por si el TTS no avisa; sin TTS listo, enseguida.
            main.postDelayed(afterAck, if (ttsReady) ACK_MAX_WAIT_MS else ACK_NO_TTS_MS)
            speakOut("Te escucho", UTT_ACK, queueIfNotReady = false)
        }
    }

    private fun onUtteranceFinished(id: String?) {
        when (id) {
            UTT_ACK -> if (listeningCommand && !transcriptionStarted) {
                main.removeCallbacks(afterAck)
                main.postDelayed(afterAck, 150L)
            }
            UTT_REPLY -> if (listeningCommand) {
                main.removeCallbacks(resumeAfterReply)
                main.postDelayed(resumeAfterReply, 700L)
            }
        }
    }

    private fun startTranscriptionOnce() {
        if (!listeningCommand || transcriptionStarted) return
        transcriptionStarted = true
        transcribeCommand()
    }

    private fun transcribeCommand() {
        val recognizer = stt ?: AndroidSpeechToText(applicationContext).also { stt = it }
        updateNotification("Escuchando tu comando…")
        main.removeCallbacks(sttTimeout)
        main.postDelayed(sttTimeout, STT_MAX_MS)
        recognizer.listen(
            onPartial = { partial ->
                if (!listeningCommand) return@listen
                val shown = maskedStatus(partial, "Te escuché")
                updateNotification("… $shown")
                AssistantState.update(AssistantState.Phase.HEARD, shown)
                if (!lockedSession) overlay?.updateStatus(shown)
            },
            onResult = { text ->
                if (!listeningCommand) return@listen
                main.removeCallbacks(sttTimeout)
                AgentLog.add("ESCUCHÉ «$text»")
                runCatching { RecentCommandsStore(applicationContext).add(text) }
                lastCommand = text
                val shown = maskedStatus("“$text”", "Te escuché")
                updateNotification("Escuché: $shown")
                AssistantState.update(AssistantState.Phase.HEARD, shown)
                if (!lockedSession) overlay?.updateStatus(shown)
                processAndRespond(text)
            },
            onError = { message ->
                if (!listeningCommand) return@listen
                main.removeCallbacks(sttTimeout)
                AgentLog.add("NO ENTENDÍ: $message")
                // Mostrar el motivo un instante y volver a esperar la palabra.
                AssistantState.update(AssistantState.Phase.REPLYING, message)
                if (!lockedSession) overlay?.updateStatus(message)
                main.removeCallbacks(resumeAfterReply)
                main.postDelayed(resumeAfterReply, 1_200L)
            },
        )
    }

    /** El reconocedor no respondió a tiempo (pasa en algunos equipos). */
    private fun onTranscriptionTimeout() {
        if (!listeningCommand) return
        stt?.cancel()
        resumeListening()
    }

    /**
     * Manda la transcripción al cerebro (LLM + tools), dice la respuesta en voz
     * alta y reanuda la escucha del wake word al terminar de hablar. El trabajo
     * de red ocurre en la corrutina; las acciones y el TTS, en el main thread.
     */
    private fun processAndRespond(command: String) {
        val processor = commandProcessor
            ?: CommandProcessor(applicationContext).also { commandProcessor = it }
        // Un agente puede tardar (abrir apps, leer pantallas): el wake lock se
        // renueva con tope y la pantalla muestra en qué paso va.
        voiceWakeLock.acquire(INTERACTION_MAX_MS)
        scope.launch {
            val reply = try {
                processor.process(command) { step -> main.post { showProgress(step) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Reply("Tuve un problema para procesar eso. Probá de nuevo.")
            }
            deliverReply(reply, processor.lastError)
        }
    }

    private fun showProgress(step: String) {
        if (!listeningCommand) return
        updateNotification(step)
        AssistantState.update(AssistantState.Phase.HEARD, step)
        if (!lockedSession) overlay?.updateStatus(step)
    }

    private fun deliverReply(answer: Reply, error: String?) {
        if (!listeningCommand) return
        val reply = answer.text
        // Si preguntó algo (ej. "¿Le mando…?"), seguir escuchando sin wake word.
        followUpPending = answer.expectsReply && followUps < MAX_FOLLOW_UPS
        lastReply = reply
        lastBrainError = error
        runCatching { RecentCommandsStore(applicationContext).addReply(reply) }
        val shown = maskedStatus(reply, "Respuesta lista")
        updateNotification(shown)
        AssistantState.update(AssistantState.Phase.REPLYING, shown)
        if (!lockedSession) overlay?.updateStatus(reply)
        // Reanudar cuando termine de hablar; con tope por si el TTS no avisa.
        main.removeCallbacks(resumeAfterReply)
        main.postDelayed(resumeAfterReply, estimatedSpeechMs(reply))
        speakOut(reply, UTT_REPLY)
    }

    /** Terminó de hablar: o escucha la respuesta a su pregunta, o vuelve a esperar el nombre. */
    private fun afterReply() {
        if (!listeningCommand) return
        if (!followUpPending) {
            resumeListening()
            return
        }
        followUpPending = false
        followUps++
        AgentLog.add("ESPERANDO tu respuesta (sin decir el nombre)")
        main.removeCallbacks(resumeAfterReply)
        voiceWakeLock.acquire(INTERACTION_MAX_MS)
        vibrate()
        AssistantState.update(AssistantState.Phase.LISTENING, "Te escucho…")
        if (!lockedSession) overlay?.updateStatus("Te escucho…")
        transcriptionStarted = true
        transcribeCommand()
    }

    private fun estimatedSpeechMs(text: String): Long =
        (2_000L + text.length * 90L).coerceAtMost(45_000L)

    private fun resumeListening() {
        main.removeCallbacks(afterAck)
        main.removeCallbacks(sttTimeout)
        main.removeCallbacks(resumeAfterReply)
        listeningCommand = false
        transcriptionStarted = false
        followUpPending = false
        overlay?.hide()
        AssistantState.idle()
        if (voiceWakeLock.isHeld) runCatching { voiceWakeLock.release() }

        // Reanudar el wake word; si el engine se perdió o cambiaron los
        // ajustes mientras tanto, reconstruirlo.
        val engine = wakeEngine
        if (engine == null || reloadPending) {
            reloadPending = false
            startListeningFresh()
            return
        }
        startEngine(engine)
    }

    private fun startListeningFresh() {
        wakeEngine?.release()
        wakeEngine = null
        startListening()
    }

    /**
     * Prueba de vida: responde la hora por TTS. Funciona con pantalla bloqueada.
     * Si el TTS todavía está inicializando, la frase queda en espera.
     */
    private fun speakTime() {
        acquireBriefWakeLock()
        val hora = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        speakOut("Son las $hora", UTT_TIME)
    }

    /** Aplica la voz/tono elegidos y dice el texto en voz alta. */
    private fun speakOut(text: String, id: String, queueIfNotReady: Boolean = true) {
        val engine = tts
        if (engine == null || !ttsReady) {
            if (queueIfNotReady) pendingSpeech = text to id
            return
        }
        VoiceSettings(this).applyTo(engine)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun acquireBriefWakeLock() {
        // Wake lock con timeout: mantiene la CPU despierta lo justo para la
        // respuesta aunque el teléfono esté en Doze.
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ControlXIA:brief")
            .acquire(10_000L)
    }

    private fun isLockedOrScreenOff(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return km.isKeyguardLocked || !pm.isInteractive
    }

    private fun launchAssistantScreen() {
        val intent = Intent(this, AssistantActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { startActivity(intent) }
    }

    /** Con el teléfono bloqueado y privacidad activada, oculta el contenido real. */
    private fun maskedStatus(text: String, generic: String): String =
        if (lockedSession && !showContentLocked) generic else text

    @Suppress("DEPRECATION")
    private fun vibrate() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun stopFromUser() {
        ServicePrefs.setEnabled(this, false)
        WatchdogReceiver.cancel(this)
        releaseVoice()
        running = false
        wakeWordActive = false
        inForeground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseVoice() {
        main.removeCallbacksAndMessages(null)
        listeningCommand = false
        transcriptionStarted = false
        overlay?.hide()
        AssistantState.idle()
        stt?.cancel()
        stt = null
        wakeEngine?.release()
        wakeEngine = null
        tts?.stop()
        if (voiceWakeLock.isHeld) runCatching { voiceWakeLock.release() }
    }

    override fun onDestroy() {
        running = false
        wakeWordActive = false
        inForeground = false
        releaseVoice()
        // Libera los ~110 MB del modelo de voz si el motor ya soltó el micrófono.
        VoskModelStore.releaseIfIdle()
        scope.cancel()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_SPEAK_TIME = "com.controlxia.app.action.SPEAK_TIME"
        const val ACTION_STOP = "com.controlxia.app.action.STOP"
        const val ACTION_RELOAD = "com.controlxia.app.action.RELOAD"

        private const val UTT_ACK = "ack"
        private const val UTT_REPLY = "reply"
        private const val UTT_TIME = "speak_time"

        private const val ACK_MAX_WAIT_MS = 2_500L
        private const val ACK_NO_TTS_MS = 300L
        private const val STT_MAX_MS = 20_000L
        private const val INTERACTION_MAX_MS = 180_000L
        private const val MAX_FOLLOW_UPS = 2
        private const val ENGINE_RETRY_MS = 10_000L

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

        /** true si el wake word está inactivo por un error (no mientras se prepara). */
        @Volatile
        var wakeWordFailed: Boolean = false
            private set

        /** Última transcripción escuchada (para feedback inmediato en la UI). */
        @Volatile
        var lastCommand: String? = null
            private set

        /** Última respuesta hablada de Xia (para mostrar en la UI). */
        @Volatile
        var lastReply: String? = null
            private set

        /** Error técnico del último pedido al LLM (null si salió bien). */
        @Volatile
        var lastBrainError: String? = null
            private set

        /**
         * Intenta arrancar el servicio. Devuelve false si Android lo bloqueó:
         * ForegroundServiceStartNotAllowedException (Android 12+) o
         * IllegalStateException (arranque desde segundo plano en 8–11).
         */
        fun tryStart(context: Context, action: String? = null): Boolean = try {
            ContextCompat.startForegroundService(
                context, Intent(context, WakeWordService::class.java).setAction(action)
            )
            true
        } catch (e: Exception) {
            false
        }

        fun start(context: Context): Boolean = tryStart(context)

        /** Apaga la escucha y evita que el watchdog o el reinicio la relancen. */
        fun stop(context: Context) {
            ServicePrefs.setEnabled(context, false)
            WatchdogReceiver.cancel(context)
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        /** Dispara la prueba de voz ("son las HH:mm") sobre el servicio activo. */
        fun speakTime(context: Context) {
            tryStart(context, ACTION_SPEAK_TIME)
        }

        /** Aplica cambios de la palabra de activación sin apagar el servicio. */
        fun reload(context: Context) {
            if (running) tryStart(context, ACTION_RELOAD)
        }
    }
}
