package com.controlxia.app.service

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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
import com.controlxia.app.actions.CameraControl
import com.controlxia.app.actions.UnlockGate
import com.controlxia.app.brain.CommandProcessor
import com.controlxia.app.brain.Reply
import com.controlxia.app.brain.StopWords
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.permissions.PermissionManager
import com.controlxia.app.ui.AssistantActivity
import com.controlxia.app.ui.MainActivity
import com.controlxia.app.voice.AndroidSpeechToText
import com.controlxia.app.voice.AssistantState
import com.controlxia.app.voice.ListeningOverlay
import com.controlxia.app.voice.RecentCommandsStore
import com.controlxia.app.voice.SpeechToText
import com.controlxia.app.voice.VoiceBridge
import com.controlxia.app.voice.VoiceSettings
import com.controlxia.app.voice.VoskModelStore
import com.controlxia.app.voice.VoskWakeWordEngine
import com.controlxia.app.voice.WakeWordEngine
import com.controlxia.app.voice.WakeWordSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Servicio de escucha persistente. Corre como Foreground Service de tipo
 * "microphone" con notificación fija, la única categoría a la que Android le
 * permite usar el micrófono de forma continua.
 *
 * Confiabilidad: START_STICKY + watchdog (AlarmManager) + BootReceiver. Si
 * Android no deja encender el micrófono desde segundo plano (Android 14+), el
 * servicio no crashea: avisa con una notificación para reactivarlo con un toque.
 *
 * Cadena de voz: wake word (Vosk) → tono corto (o "Te escucho") →
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

    /**
     * Seguir escuchando sin wake word al terminar de hablar: porque hizo una
     * pregunta o por el modo conversación. Mientras dura la ventana
     * ([followUpDeadline]), el silencio no es un error: se sigue escuchando
     * hasta que se cumple el tiempo y ahí vuelve a esperar el nombre.
     */
    private var followUpPending = false
    private var followUps = 0
    private var inFollowUp = false
    private var followUpWindowMs = 0L
    private var followUpDeadline = 0L
    private val retryWakeWord = Runnable { if (!listeningCommand) startListening() }

    /** El pedido en curso (pensar y actuar), para poder frenarlo. */
    private var processingJob: Job? = null

    /**
     * Mientras trabaja o habla escucha "pará", "basta"… y su nombre. Es otra
     * instancia del motor de voz: en esas etapas el micrófono está libre.
     */
    private var stopEngine: WakeWordEngine? = null

    /** Una herramienta está usando el micrófono (grabando un audio): no escuchar "pará". */
    private var micLent = false

    /** Quién espera que termine de hablar una frase (por id de la frase). */
    private val utteranceWaiters = mutableMapOf<String, () -> Unit>()

    /** El tono de "te escucho": mucho más corto que decirlo, así no se pierde nada. */
    private var toneGenerator: ToneGenerator? = null

    override fun onCreate() {
        super.onCreate()
        overlay = ListeningOverlay(this).apply { onTap = { main.post { stopCurrent("tocaste el aviso") } } }
        // Mientras la cámara graba, el motor de voz escucha "terminá el video".
        CameraControl.onRecordingChanged = {
            main.post { if (listeningCommand) reloadPending = true else startListeningFresh() }
        }
        VoiceBridge.speaker = { text -> speakAndWait(text) }
        VoiceBridge.micClaim = { busy ->
            micLent = busy
            if (busy) {
                stopStopListener()
                showProgress("🎙 Grabando… hablá ahora")
            } else if (processingJob?.isActive == true) {
                startStopListener()
            }
        }
        // Se desbloqueó en medio de un pedido (para usar una app): seguir con la burbuja.
        UnlockGate.onUnlocked = { main.post { onPhoneUnlocked() } }
        agentName = WakeWordSettings(this).agentName
        tts = TextToSpeech(this) { status -> main.post { onTtsInit(status) } }
    }

    private fun onPhoneUnlocked() {
        if (!listeningCommand || !lockedSession) return
        lockedSession = false
        AssistantState.unlocked()
        overlay?.show(agentName, AssistantState.state.value.status.ifBlank { "Sigo con tu pedido…" })
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
        // Levantaste las dos manos mirando un video: ya está en pausa, preguntar.
        if (intent?.action == ACTION_HANDS_UP) {
            if (inForeground) main.post { if (!listeningCommand) startInteraction("¿Qué necesitás?") }
            return START_STICKY
        }
        // Frenar lo que está haciendo (aviso, pantalla de bloqueo), sin apagar la escucha.
        if (intent?.action == ACTION_INTERRUPT) {
            if (inForeground) main.post { stopCurrent("tocaste Frenar") }
            return START_STICKY
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
        val builder = NotificationCompat.Builder(this, XiaApplication.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (listeningCommand) {
            // En plena interacción: frenar lo que está haciendo, sin apagar el servicio.
            val interrupt = PendingIntent.getService(
                this, 3,
                Intent(this, WakeWordService::class.java).setAction(ACTION_INTERRUPT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.notif_action_interrupt), interrupt)
        } else {
            builder.addAction(0, getString(R.string.notif_action_test), speakTime)
        }
        return builder.addAction(0, getString(R.string.notif_action_stop), stop).build()
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
        val recording = CameraControl.recording
        val engine = VoskWakeWordEngine(
            context = applicationContext,
            phrase = settings.spokenWakeWord(),
            sensitivity = settings.sensitivity,
            // Grabando, el reconocedor de Google no recibe audio: "terminá el video" se oye acá.
            commands = if (recording) CameraControl.STOP_VIDEO_PHRASES else emptyList(),
            echoCancel = recording,
            onCommand = { phrase -> main.post { onStopVideoHeard(phrase) } },
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
            if (CameraControl.recording) {
                // Grabando: no hablar (se grabaría) ni transcribir (no llega audio).
                AgentLog.add("GRABANDO: para terminar decí «terminá el video»")
                vibrate(40)
                wakeEngine?.let { startEngine(it) }
                return@post
            }
            startInteraction(null)
        }
    }

    /**
     * Empieza a atender y transcribe el pedido. Confirma con [ack] en voz alta
     * (una pregunta, como "¿Qué necesitás?") o, sin [ack], con un tono corto y
     * escucha enseguida: decir "Te escucho" tarda un segundo y en ese segundo
     * se perdía el principio del pedido. Se puede volver a la voz en ajustes.
     */
    private fun startInteraction(ack: String?) {
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

        val spoken = ack ?: if (settings.spokenAck) "Te escucho" else null
        if (spoken == null || !ttsReady) {
            beep()
            main.postDelayed(afterAck, TONE_MS)
            return
        }
        // Transcribir al terminar de hablar (así no se transcribe a sí mismo).
        // Tope por si el TTS no avisa.
        main.postDelayed(afterAck, ACK_MAX_WAIT_MS)
        speakOut(spoken, UTT_ACK, queueIfNotReady = false)
    }

    /** Tono corto de "te escucho" (más suave para seguir una conversación). */
    private fun beep(soft: Boolean = false) {
        runCatching {
            val tone = toneGenerator ?: ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
                .also { toneGenerator = it }
            tone.startTone(if (soft) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP, TONE_LENGTH_MS)
        }
    }

    private fun onUtteranceFinished(id: String?) {
        utteranceWaiters.remove(id)?.invoke()
        when (id) {
            UTT_ACK -> if (listeningCommand && !transcriptionStarted) {
                main.removeCallbacks(afterAck)
                main.postDelayed(afterAck, 150L)
            }
            UTT_REPLY -> if (listeningCommand) {
                main.removeCallbacks(resumeAfterReply)
                main.postDelayed(resumeAfterReply, AFTER_SPEECH_MS)
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
                inFollowUp = false
                AgentLog.add("ESCUCHÉ «$text»")
                runCatching { RecentCommandsStore(applicationContext).add(text) }
                lastCommand = text
                val shown = maskedStatus("“$text”", "Te escuché")
                updateNotification("Escuché: $shown")
                AssistantState.update(AssistantState.Phase.HEARD, shown)
                if (!lockedSession) overlay?.updateStatus(shown)
                processAndRespond(text)
            },
            onError = { message, noSpeech ->
                if (!listeningCommand) return@listen
                main.removeCallbacks(sttTimeout)
                if (inFollowUp && noSpeech) {
                    if (SystemClock.elapsedRealtime() < followUpDeadline - 1_000L) {
                        // Silencio, pero todavía hay tiempo: seguir escuchando.
                        main.postDelayed({ if (listeningCommand && inFollowUp) transcribeCommand() }, 150L)
                    } else {
                        AgentLog.add("FIN de la conversación (no dijiste nada)")
                        resumeListening()
                    }
                    return@listen
                }
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
        startStopListener()
        processingJob = scope.launch {
            val reply = try {
                processor.process(command) { step -> main.post { showProgress(step) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Reply("Tuve un problema para procesar eso. Probá de nuevo.")
            }
            processingJob = null
            deliverReply(reply, processor.lastError)
        }
    }

    /** Para las herramientas: dice [text] y vuelve cuando terminó de hablar. */
    private suspend fun speakAndWait(text: String) = suspendCancellableCoroutine<Unit> { cont ->
        main.post {
            if (!ttsReady) {
                if (cont.isActive) cont.resume(Unit)
                return@post
            }
            val id = "bridge_${SystemClock.uptimeMillis()}"
            utteranceWaiters[id] = { if (cont.isActive) cont.resume(Unit) }
            showProgress(text)
            speakOut(text, id)
        }
    }

    /** Escucha "pará", "basta"… y el nombre mientras trabaja y mientras responde. */
    private fun startStopListener() {
        if (stopEngine != null || micLent || !PermissionManager.hasRecordAudio(this)) return
        val settings = WakeWordSettings(this)
        var engine: WakeWordEngine? = null
        engine = VoskWakeWordEngine(
            context = applicationContext,
            phrase = settings.spokenWakeWord(),
            sensitivity = settings.sensitivity,
            commands = StopWords.SPOKEN,
            echoCancel = true,
            onCommand = { word -> main.post { if (stopEngine === engine) stopCurrent("dijiste «$word»") } },
        )
        stopEngine = engine
        engine.start(
            onDetected = { main.post { if (stopEngine === engine) onCalledWhileBusy() } },
            onError = { message, _ -> AgentLog.add("No puedo escuchar «pará» ahora: $message") },
        )
    }

    private fun stopStopListener() {
        val engine = stopEngine ?: return
        stopEngine = null
        engine.release()
    }

    /**
     * Frena lo que esté haciendo: pensar, actuar en una app o hablar. Lo que
     * estaba haciendo no sigue, y el cerebro queda enterado de que lo frenaron
     * y de qué alcanzó a hacer. Después vuelve a esperar el nombre.
     */
    private fun stopCurrent(reason: String) {
        if (!listeningCommand) return
        AgentLog.add("FRENAR: $reason")
        stopStopListener()
        stt?.cancel()
        main.removeCallbacks(afterAck)
        main.removeCallbacks(sttTimeout)
        main.removeCallbacks(resumeAfterReply)
        val wasWorking = processingJob?.isActive == true
        if (wasWorking) {
            commandProcessor?.interrupt()
            processingJob?.cancel()
        }
        processingJob = null
        tts?.stop()
        vibrate(60)
        followUpPending = false
        inFollowUp = false
        if (wasWorking) {
            // Avisar que frenó: lo que estaba haciendo quedó a medias a propósito.
            showProgress("Frené")
            main.postDelayed(resumeAfterReply, 2_500L)
            speakOut("Listo, paré.", UTT_REPLY)
        } else {
            resumeListening()
        }
    }

    /**
     * Lo llamaron por el nombre mientras trabajaba o hablaba: deja lo que estaba
     * haciendo y escucha el pedido nuevo enseguida (como "Alexa" en medio de una
     * respuesta).
     */
    private fun onCalledWhileBusy() {
        if (!listeningCommand) return
        stopStopListener()
        val wasWorking = processingJob?.isActive == true
        AgentLog.add("INTERRUMPIDO: me llamaste mientras ${if (wasWorking) "trabajaba" else "hablaba"}")
        if (wasWorking) {
            commandProcessor?.interrupt()
            processingJob?.cancel()
        }
        processingJob = null
        tts?.stop()
        main.removeCallbacks(resumeAfterReply)
        followUpPending = false
        inFollowUp = false
        followUps = 0
        vibrate()
        AssistantState.update(AssistantState.Phase.LISTENING, "Te escucho…")
        if (!lockedSession) overlay?.updateStatus("Te escucho…")
        transcriptionStarted = true
        transcribeCommand()
    }

    /** "Terminá el video" mientras la cámara graba (lo oye el motor de voz, sin internet). */
    private fun onStopVideoHeard(phrase: String) {
        AgentLog.add("ESCUCHÉ «$phrase» mientras grababa")
        vibrate(60)
        scope.launch {
            val error = CameraControl(applicationContext).stopVideo()
            speakOut(error ?: "Listo, el video quedó guardado.", UTT_TIME)
            if (!listeningCommand) startListeningFresh()
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
        val settings = WakeWordSettings(this)
        // Seguir escuchando sin wake word si preguntó algo ("¿Le mando…?") o
        // en modo conversación, salvo que el usuario se haya despedido.
        followUpPending = !answer.endsConversation && followUps < MAX_FOLLOW_UPS &&
            (answer.expectsReply || settings.conversationMode)
        val window = settings.conversationSeconds * 1_000L
        followUpWindowMs = if (answer.expectsReply) maxOf(window, QUESTION_WINDOW_MS) else window
        lastBrainError = error
        if (reply.isBlank()) {
            // Sin hablar: despedidas ("listo", "nada más") o comandos que se ven
            // ("atrás", "bajá", "tocá 5"), que muestran un texto y siguen escuchando.
            answer.shown?.let { shown ->
                vibrate(30)
                updateNotification(maskedStatus(shown, "Listo"))
                AssistantState.update(AssistantState.Phase.REPLYING, maskedStatus(shown, "Listo"))
                if (!lockedSession) overlay?.updateStatus(shown)
            }
            main.removeCallbacks(resumeAfterReply)
            main.postDelayed(resumeAfterReply, if (answer.shown != null) 400L else 0L)
            return
        }
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
        stopStopListener()
        if (!followUpPending) {
            resumeListening()
            return
        }
        followUpPending = false
        followUps++
        inFollowUp = true
        followUpDeadline = SystemClock.elapsedRealtime() + followUpWindowMs
        AgentLog.add("ESPERANDO otro pedido ${followUpWindowMs / 1_000} s (sin decir el nombre)")
        main.removeCallbacks(resumeAfterReply)
        voiceWakeLock.acquire(INTERACTION_MAX_MS)
        vibrate(40)
        beep(soft = true)
        AssistantState.update(AssistantState.Phase.LISTENING, "Te escucho…")
        if (!lockedSession) overlay?.updateStatus("Te escucho…")
        transcriptionStarted = true
        main.postDelayed({ if (listeningCommand && inFollowUp) transcribeCommand() }, TONE_MS)
    }

    private fun estimatedSpeechMs(text: String): Long =
        (2_000L + text.length * 90L).coerceAtMost(45_000L)

    private fun resumeListening() {
        stopStopListener()
        main.removeCallbacks(afterAck)
        main.removeCallbacks(sttTimeout)
        main.removeCallbacks(resumeAfterReply)
        listeningCommand = false
        transcriptionStarted = false
        followUpPending = false
        inFollowUp = false
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
    private fun vibrate(millis: Long = 120) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
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
        stopStopListener()
        processingJob?.cancel()
        processingJob = null
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
        VoiceBridge.speaker = null
        VoiceBridge.micClaim = null
        UnlockGate.onUnlocked = null
        CameraControl.onRecordingChanged = null
        running = false
        wakeWordActive = false
        inForeground = false
        releaseVoice()
        // Libera los ~110 MB del modelo de voz si el motor ya soltó el micrófono.
        VoskModelStore.releaseIfIdle()
        scope.cancel()
        tts?.shutdown()
        tts = null
        toneGenerator?.release()
        toneGenerator = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_SPEAK_TIME = "com.controlxia.app.action.SPEAK_TIME"
        const val ACTION_STOP = "com.controlxia.app.action.STOP"
        const val ACTION_RELOAD = "com.controlxia.app.action.RELOAD"
        const val ACTION_INTERRUPT = "com.controlxia.app.action.INTERRUPT"
        const val ACTION_HANDS_UP = "com.controlxia.app.action.HANDS_UP"

        private const val UTT_ACK = "ack"
        private const val UTT_REPLY = "reply"
        private const val UTT_TIME = "speak_time"

        private const val ACK_MAX_WAIT_MS = 2_500L
        /** Cuánto después del tono empieza a transcribir (que no se oiga el tono). */
        private const val TONE_MS = 160L
        private const val TONE_LENGTH_MS = 90
        private const val TONE_VOLUME = 60
        /** Pausa entre que termina de hablar y vuelve a escuchar (sin oírse a sí mismo). */
        private const val AFTER_SPEECH_MS = 250L
        private const val STT_MAX_MS = 30_000L
        private const val INTERACTION_MAX_MS = 180_000L
        /** Tope de turnos seguidos sin wake word (por si la tele "conversa" sola). */
        private const val MAX_FOLLOW_UPS = 40

        /** Tiempo mínimo para contestar una pregunta ("¿Le mando…?"). */
        private const val QUESTION_WINDOW_MS = 10_000L
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

        /** Levantaste las dos manos: preguntar qué necesitás. */
        fun handsUp(context: Context) {
            if (running) tryStart(context, ACTION_HANDS_UP)
        }

        /** Frena lo que el asistente esté haciendo o diciendo (sin apagar la escucha). */
        fun interrupt(context: Context) {
            if (running) tryStart(context, ACTION_INTERRUPT)
        }

        /** Aplica cambios de la palabra de activación sin apagar el servicio. */
        fun reload(context: Context) {
            if (running) tryStart(context, ACTION_RELOAD)
        }
    }
}
