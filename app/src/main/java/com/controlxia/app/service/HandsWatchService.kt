package com.controlxia.app.service

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.widget.Toast
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.controlxia.app.R
import com.controlxia.app.XiaApplication
import com.controlxia.app.actions.DeviceControl
import com.controlxia.app.actions.Gestures
import com.controlxia.app.actions.MediaApps
import com.controlxia.app.actions.VideoMessage
import com.controlxia.app.actions.WhatsAppUi
import com.controlxia.app.brain.PendingAction
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.permissions.A11yHealth
import com.controlxia.app.permissions.PermissionManager
import com.controlxia.app.voice.AirGestures
import com.controlxia.app.voice.AssistantState
import com.controlxia.app.voice.ConfirmState
import com.controlxia.app.voice.FaceSigns
import com.controlxia.app.voice.Feedback
import com.controlxia.app.voice.HandAnalyzer
import com.controlxia.app.voice.HandMotion
import com.controlxia.app.voice.HandSigns
import com.controlxia.app.voice.WakeWordSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * Gestos con la cámara frontal, como los gestos aéreos de los teléfonos:
 *
 * - ☝️ / 👇 índice arriba o abajo: el reel siguiente o anterior; 👉 / 👈 al
 *   costado: foto o historia ([AirGestures]). La app hace el mismo deslizamiento
 *   que el dedo en la pantalla. Una vez por seña: hay que relajar la mano entre una y otra.
 * - ✊ puño: pausa o sigue (en un chat de WhatsApp, graba un video), 👍 me gusta
 *   (dos toques en el video), ✌️ pausa y te escucha, 🙌 las dos manos pausan y
 *   preguntan "¿Qué necesitás?".
 * - Cuando el asistente pregunta algo: guiño o asentir = sí, negar = no, 👍/👎.
 * - Con el teléfono bloqueado y música sonando: el dedo a los costados cambia
 *   de tema, arriba y abajo el volumen; el puño pausa o sigue.
 *
 * La cámara mira solo con la pantalla prendida y algo reproduciéndose, una app
 * de videos adelante, una pregunta esperando, una prueba, o el ajuste "con
 * cualquier app"; se apaga a los segundos de que no. Todo se analiza en el
 * teléfono: no se guardan ni se mandan imágenes.
 */
class HandsWatchService : LifecycleService() {

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var analyzer: HandAnalyzer? = null
    private var provider: ProcessCameraProvider? = null
    private var useCase: ImageAnalysis? = null
    private var cameraOn = false
    private var lastFrameAt = 0L
    private var streak = 0
    private var lastTrigger = 0L
    private var lastHandAt = 0L
    private val air = AirGestures()
    private val feedback by lazy { Feedback(this) }

    @Volatile
    private var gesturesOn = true

    @Volatile
    private var always = false

    @Volatile
    private var confirmOn = true
    private val faceSigns = FaceSigns()
    private val answerSigns = HandSigns.Trigger(framesNeeded = 3, repeatable = emptySet())

    // ---- Videos con gestos en WhatsApp ----
    @Volatile
    private var videoOn = true
    private var videoCapture: VideoCapture<Recorder>? = null

    @Volatile
    private var recording: Recording? = null
    private var recordingChat: String? = null
    private var recordingFile: File? = null
    private var recordingStartedAt = 0L
    private var recordingLimitMs = MAX_VIDEO_MS

    /** Cuándo pausó un gesto lo que sonaba (la palma solo hace "sigue" después de eso). */
    private var pausedByGestureAt = 0L

    /** El video grabado esperando 👍 o 👎, cuando la escucha no anda para preguntarlo en voz alta. */
    @Volatile
    private var localPending: PendingAction? = null
    private var localPendingUntil = 0L
    private val stopVideoAtLimit = Runnable { stopVideo(LIMIT) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            WakeWordSettings(this).handsPause = false
            stopSelf()
            return START_NOT_STICKY
        }
        // Cámara, y micrófono si se puede (para el sonido de los videos con gestos).
        val camera = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        val withMic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && PermissionManager.hasRecordAudio(this)) {
            camera or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            camera
        }
        val started = runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), withMic) }
            .recoverCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), camera) }
        if (started.isFailure) {
            // Sin permiso de cámara, o Android no deja (arranque desde segundo plano).
            AgentLog.add("MANOS no pude arrancar: ${started.exceptionOrNull()?.message}")
            publish("no pude arrancar: ${started.exceptionOrNull()?.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        current = WeakReference(this)
        readSettings()
        if (analyzer == null) analyzer = HandAnalyzer.create(this)
        if (provider == null) {
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                provider = runCatching { future.get() }.getOrNull()
                // Recién ahora se puede prender la cámara.
                evaluate()
            }, ContextCompat.getMainExecutor(this))
            watchPlayback()
        }
        return START_STICKY
    }

    private fun readSettings() {
        val settings = WakeWordSettings(this)
        gesturesOn = settings.handGestures
        always = settings.handsAlways
        confirmOn = settings.confirmGestures
        videoOn = settings.videoGestures
    }

    /**
     * Reacciona a avisos del sistema (reproducción, app adelante, pantalla,
     * preguntas) en vez de revisar todo el tiempo; un control lento cubre lo
     * que se escape.
     */
    private fun watchPlayback() {
        audio.registerAudioPlaybackCallback(playbackCallback, handler)
        XiaAccessibilityService.foregroundListeners += foregroundListener
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        lifecycleScope.launch {
            while (isActive) {
                readSettings()
                evaluate()
                delay(HEARTBEAT_MS)
            }
        }
        // Una pregunta que espera "sí" o "no": la cámara mira para contestar con un gesto.
        lifecycleScope.launch {
            ConfirmState.question.collect {
                faceSigns.reset()
                answerSigns.reset()
                evaluate()
            }
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) = evaluate()
    }
    private val foregroundListener: (String) -> Unit = { handler.post { evaluate() } }
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = evaluate()
    }
    private val turnOffIfQuiet = Runnable { if (!wanted() && cameraOn) unbindCamera() }

    private fun musicPlaying(): Boolean = runCatching { audio.isMusicActive }.getOrDefault(false)

    private fun locked(): Boolean = keyguard?.isKeyguardLocked == true

    /**
     * Hace falta mirar: pantalla prendida y algo reproduciéndose, una app de
     * videos adelante, una pregunta esperando el "sí", una prueba desde
     * Ajustes, o el ajuste "con cualquier app". Bloqueado, solo con música (la
     * cámara frontal también la usa el desbloqueo con la cara).
     */
    private fun wanted(): Boolean {
        if (recording != null || videoCapture != null || localPending != null) return true
        if (paused || !power.isInteractive) return false
        if (locked()) return musicPlaying() || (confirmOn && ConfirmState.waiting)
        return always || (confirmOn && ConfirmState.waiting) || musicPlaying() ||
            XiaAccessibilityService.foregroundPackage in MediaApps.VIDEO || (videoOn && whatsAppInFront())
    }

    private fun whatsAppInFront(): Boolean = XiaAccessibilityService.foregroundPackage in WhatsAppUi.PACKAGES

    /** Por qué la cámara está como está (para Ajustes). */
    private fun reason(): String = when {
        recording != null -> "grabando un video para «${recordingChat.orEmpty()}»"
        localPending != null -> "esperando 👍 para mandar el video"
        paused -> "en pausa: la está usando la prueba"
        !power.isInteractive -> "apagada: la pantalla está apagada"
        analyzer == null -> "apagada: falta el modelo de gestos (activá de nuevo «Señas con la cámara»)"
        confirmOn && ConfirmState.waiting -> "mirando: esperando tu sí o no"
        locked() && musicPlaying() -> "mirando: bloqueado con música"
        locked() -> "apagada: el teléfono está bloqueado y no suena nada"
        always -> "mirando: con cualquier app"
        musicPlaying() -> "mirando: se está reproduciendo algo"
        XiaAccessibilityService.foregroundPackage in MediaApps.VIDEO -> "mirando: app de videos adelante"
        videoOn && whatsAppInFront() -> "mirando: WhatsApp adelante (✊ para grabar un video)"
        XiaAccessibilityService.instance == null ->
            "apagada: no suena nada, y sin el control de apps no sé si hay una app de videos adelante"
        else -> "apagada: no hay video ni música"
    }

    private fun evaluate() {
        if (cameraOn || !wanted()) publish(reason())
        if (wanted()) {
            handler.removeCallbacks(turnOffIfQuiet)
            if (!cameraOn) bindCamera()
        } else if (cameraOn) {
            // Pantalla apagada o prueba: enseguida; si no, unos segundos (puede ser un corte entre videos).
            handler.removeCallbacks(turnOffIfQuiet)
            handler.postDelayed(turnOffIfQuiet, if (power.isInteractive && !paused) CAMERA_OFF_AFTER_MS else 0L)
        }
    }

    @SuppressLint("MissingPermission")
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun bindCamera() {
        val cameras = provider ?: return
        if (analyzer == null) analyzer = HandAnalyzer.create(this)
        if (analyzer == null) {
            publish(reason())
            return
        }
        val analysis = analysisUseCase(cameras, lowFps = true)
        val ok = runCatching {
            useCase?.let { cameras.unbind(it) }
            cameras.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }
        useCase = analysis.takeIf { ok.isSuccess }
        cameraOn = ok.isSuccess
        AgentLog.add(if (cameraOn) "MANOS cámara prendida (${reason()})" else "MANOS no pude usar la cámara: ${ok.exceptionOrNull()?.message}")
        publish(if (cameraOn) reason() else "no pude usar la cámara: ${ok.exceptionOrNull()?.message}")
    }

    /**
     * El análisis de cuadros para las señas. [lowFps]: que la cámara no saque
     * 30 cuadros por segundo (alcanzan ~10); grabando un video, los de siempre.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun analysisUseCase(cameras: ProcessCameraProvider, lowFps: Boolean): ImageAnalysis {
        val builder = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(480, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        if (lowFps) {
            lowestFpsRange(cameras)?.let { range ->
                Camera2Interop.Extender(builder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            }
        }
        return builder.build().also { it.setAnalyzer(analysisExecutor) { image -> analyze(image) } }
    }

    /** El rango de cuadros por segundo más bajo que acepta la cámara frontal (al menos 15). */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun lowestFpsRange(cameras: ProcessCameraProvider): Range<Int>? = runCatching {
        val front = cameras.availableCameraInfos.firstOrNull {
            CameraSelector.DEFAULT_FRONT_CAMERA.filter(listOf(it)).isNotEmpty()
        } ?: return@runCatching null
        Camera2CameraInfo.from(front)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.upper >= MIN_CAMERA_FPS }
            ?.minWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
    }.getOrNull()

    private fun unbindCamera() {
        // Solo lo propio: la pantalla de prueba puede estar usando la cámara.
        useCase?.let { runCatching { provider?.unbind(it) } }
        useCase = null
        cameraOn = false
        streak = 0
        air.reset()
        AgentLog.add("MANOS cámara apagada (${reason()})")
        publish(reason())
    }

    private fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            val confirming = confirmOn && ConfirmState.waiting
            // ~10 cuadros por segundo con una mano a la vista (para ver el movimiento) o
            // esperando un sí o un no; ~3 si hace rato que no hay ninguna.
            val interval = if (confirming || now - lastHandAt < ACTIVE_WINDOW_MS) ACTIVE_FRAME_MS else IDLE_FRAME_MS
            if (now - lastFrameAt < interval) return
            lastFrameAt = now
            val engine = analyzer ?: return
            if (confirming) {
                confirmByGesture(engine.analyze(HandAnalyzer.upright(image), withFace = true), now)
                return
            }
            // Mientras el asistente atiende, no hace falta mirar.
            if (AssistantState.state.value.phase != AssistantState.Phase.IDLE) {
                streak = 0
                air.reset()
                return
            }
            val result = engine.analyze(HandAnalyzer.upright(image))
            if (result.hands.isNotEmpty()) lastHandAt = now
            // Grabando un video: solo se mira si querés terminar.
            if (recording != null) return watchWhileRecording(result, now)
            localPending?.let { return answerVideo(it, result, now) }
            // Dos palmas abiertas levantadas: pausa y pregunta.
            streak = if (result.raised >= 2) streak + 1 else 0
            if (streak >= FRAMES_NEEDED && now - lastTrigger > COOLDOWN_MS) {
                streak = 0
                lastTrigger = now
                air.reset()
                onHandsUp()
                return
            }
            if (!gesturesOn || result.raised >= 2) {
                air.reset()
                return
            }
            // Usando el teléfono (tocando, escribiendo, deslizando): la mano frente a la cámara es la que lo usa.
            if (XiaAccessibilityService.userActiveWithin(BUSY_QUIET_MS)) {
                air.reset()
                return
            }
            // La mano que se presenta a la cámara (entera y a buena distancia), si hay: ☝️👇👉👈, ✊, 👍, ✌️.
            val hand = result.hands.firstOrNull { it.presented } ?: result.hands.firstOrNull()
            when (val event = air.feed(hand?.sign, now, presented = hand?.presented ?: false, sure = hand?.sure ?: false)) {
                is AirGestures.Event.Swipe -> return act(event.swipe)
                is AirGestures.Event.Sign -> return perform(event.sign)
                null -> Unit
            }
            val sign = hand?.takeIf { it.presented }?.sign?.takeIf { it in SHOWN } ?: HandSigns.Sign.NONE
            if (sign != HandSigns.Sign.NONE) seen(SIGN_NAMES[sign] ?: sign.name)
        } catch (e: Exception) {
            AgentLog.add("MANOS error al analizar: ${e.message}")
        } finally {
            image.close()
        }
    }

    /**
     * Hay una pregunta esperando: guiño o asentir = sí, negar = no; también
     * pulgar arriba o abajo. Mientras tanto no se hacen las otras señas.
     */
    private fun confirmByGesture(result: HandAnalyzer.Result, now: Long) {
        faceSigns.feed(result.face, now)?.let { answer ->
            answer(answer == FaceSigns.Answer.YES, if (answer == FaceSigns.Answer.YES) "😉 sí" else "🙅 no")
            return
        }
        val sign = result.hands.map { it.sign }
            .firstOrNull { it == HandSigns.Sign.THUMB_UP || it == HandSigns.Sign.THUMB_DOWN } ?: HandSigns.Sign.NONE
        answerSigns.feed(sign, now)?.let { answer(it == HandSigns.Sign.THUMB_UP, if (it == HandSigns.Sign.THUMB_UP) "👍 sí" else "👎 no") }
    }

    private fun answer(yes: Boolean, shown: String) {
        AgentLog.add("MANOS respuesta con gesto: $shown")
        seen(shown)
        show(shown)
        feedback.vibrate(40)
        WakeWordService.answer(this, if (yes) "sí" else "no")
    }

    private fun seen(sign: String) {
        live.value = live.value.copy(seen = sign, seenAt = SystemClock.elapsedRealtime())
    }

    private fun publish(reason: String) {
        live.value = live.value.copy(camera = cameraOn, reason = reason)
    }

    /** Un cartel corto con lo que entendió, para saber que vio el gesto aunque la app no reaccione. */
    private fun show(text: String) {
        handler.post { runCatching { Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show() } }
    }

    /** Un deslizamiento en el aire: el mismo deslizamiento con el dedo (o, bloqueado, música y volumen). */
    private fun act(swipe: HandMotion.Swipe) {
        val shown = when (swipe) {
            HandMotion.Swipe.UP -> "⬆️ siguiente"
            HandMotion.Swipe.DOWN -> "⬇️ anterior"
            HandMotion.Swipe.LEFT -> "⬅️"
            HandMotion.Swipe.RIGHT -> "➡️"
        }
        seen("$shown (deslizar)")
        feedback.vibrate(25)
        val device = DeviceControl(this)
        if (locked()) {
            // Bloqueado no se puede tocar la pantalla de otras apps: música y volumen.
            when (swipe) {
                HandMotion.Swipe.LEFT -> device.media(DeviceControl.Media.NEXT)
                HandMotion.Swipe.RIGHT -> device.media(DeviceControl.Media.PREVIOUS)
                HandMotion.Swipe.UP -> device.volumeStep(1)
                HandMotion.Swipe.DOWN -> device.volumeStep(-1)
            }
            AgentLog.add("MANOS deslizar ${swipe.name} (bloqueado: música/volumen)")
            show(shown)
            return
        }
        val service = XiaAccessibilityService.instance
        if (service == null) {
            AgentLog.add("MANOS deslizar ${swipe.name}, pero ${A11yHealth.problem(A11yHealth.state(this))}")
            a11yMissing()
            return
        }
        val ok = when (swipe) {
            HandMotion.Swipe.UP -> Gestures.flingVertical(service, up = true)
            HandMotion.Swipe.DOWN -> Gestures.flingVertical(service, up = false)
            HandMotion.Swipe.LEFT -> Gestures.flingHorizontal(service, toLeft = true)
            HandMotion.Swipe.RIGHT -> Gestures.flingHorizontal(service, toLeft = false)
        }
        AgentLog.add("MANOS deslizar ${swipe.name} → ${if (ok) "hecho" else "Android no dejó hacer el gesto"}")
        show(shown)
    }

    /**
     * Hace lo que pide la seña, solo donde tiene sentido: ✊ graba un video en
     * un chat de WhatsApp o pausa/sigue lo que se ve o se escucha, 👍 es "me
     * gusta" en apps de videos, ✌️ te escucha. Si no corresponde, no hace nada
     * (y queda anotado).
     */
    private fun perform(sign: HandSigns.Sign) {
        if (sign == HandSigns.Sign.FIST && videoOn && !locked() && whatsAppInFront()) return onFist()
        val now = SystemClock.elapsedRealtime()
        val done: String? = when (sign) {
            HandSigns.Sign.FIST -> playPause(now)
            HandSigns.Sign.THUMB_UP -> if (!locked() && XiaAccessibilityService.foregroundPackage in MediaApps.VIDEO) {
                like()
                "me gusta"
            } else {
                null.also { AgentLog.add("MANOS 👍 fuera de una app de videos: no hago nada") }
            }
            HandSigns.Sign.VICTORY -> {
                DeviceControl(this).media(DeviceControl.Media.PAUSE)
                WakeWordService.listenNow(this)
                "te escucho"
            }
            else -> null
        }
        done ?: return
        AgentLog.add("MANOS seña ${sign.name}: $done")
        feedback.vibrate(25)
        show(SIGN_NAMES[sign] ?: sign.name)
    }

    /**
     * ✊ pausa o sigue: en una app de videos, como el botón de pausa; fuera de
     * ellas pausa lo que suena y sigue solo si lo pausó un gesto (no arranca
     * música sola). null si no hizo nada.
     */
    private fun playPause(now: Long): String? {
        val device = DeviceControl(this)
        return when {
            XiaAccessibilityService.foregroundPackage in MediaApps.VIDEO -> {
                device.media(DeviceControl.Media.TOGGLE)
                "pausa o sigue el video"
            }
            musicPlaying() -> {
                device.media(DeviceControl.Media.PAUSE)
                pausedByGestureAt = now
                "pausé lo que sonaba"
            }
            now - pausedByGestureAt < RESUME_WINDOW_MS -> {
                device.media(DeviceControl.Media.PLAY)
                pausedByGestureAt = 0L
                "sigue lo que había pausado"
            }
            else -> null.also { AgentLog.add("MANOS ✊ sin video ni música: no hago nada") }
        }
    }

    // ---- Videos con gestos en WhatsApp ----

    /** ✊ sostenido con un chat de WhatsApp abierto: empieza a grabar un video para ese chat. */
    private fun onFist() {
        val front = XiaAccessibilityService.foregroundPackage
        when {
            !videoOn -> return AgentLog.add("MANOS ✊, pero los videos con gestos están apagados")
            locked() -> return AgentLog.add("MANOS ✊ con el teléfono bloqueado: no grabo")
            !whatsAppInFront() -> return AgentLog.add("MANOS ✊ fuera de WhatsApp (adelante: ${front ?: "no sé"})")
        }
        // Sin el nombre del chat se graba igual, y al mandarlo se elige en WhatsApp.
        val chat = WhatsAppUi(this).openChatTitle().orEmpty()
        AgentLog.add("MANOS ✊ en WhatsApp: grabo un video para «${chat.ifEmpty { "el chat que elijas" }}»")
        feedback.vibrate(40)
        show("✊ Empiezo a grabar…")
        handler.post { startVideo(chat) }
    }

    /** Suma la grabación a la cámara que ya mira las señas (así se puede terminar con un gesto). */
    private fun startVideo(chat: String) {
        if (recording != null || videoCapture != null) return
        val cameras = provider ?: return
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .build()
        val capture = VideoCapture.withOutput(recorder)
        val analysis = analysisUseCase(cameras, lowFps = false)
        val bound = runCatching {
            useCase?.let { cameras.unbind(it) }
            cameras.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis, capture)
        }
        // Algunas cámaras no pueden grabar y mirar las señas a la vez: graba solo, con un tope corto.
        var gestureStop = true
        if (bound.isFailure) {
            AgentLog.add("MANOS la cámara no graba y mira a la vez (${bound.exceptionOrNull()?.message}): grabo sin mirar")
            gestureStop = false
            val alone = runCatching { cameras.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, capture) }
            if (alone.isFailure) {
                AgentLog.add("MANOS no pude grabar video: ${alone.exceptionOrNull()?.message}")
                show("No pude grabar el video con esta cámara")
                useCase = null
                cameraOn = false
                evaluate()
                return
            }
        }
        recordingLimitMs = if (gestureStop) MAX_VIDEO_MS else BLIND_VIDEO_MS
        useCase = analysis.takeIf { gestureStop }
        videoCapture = capture
        recordingChat = chat
        val file = File(VideoMessage.dir(this), "video_${System.currentTimeMillis()}.mp4")
        recordingFile = file
        // Que la escucha del nombre suelte el micrófono antes de grabar el sonido.
        WakeWordService.lendMicForVideo(true)
        handler.postDelayed({ beginRecording(recorder, file, chat) }, MIC_HANDOFF_MS)
    }

    @SuppressLint("MissingPermission")
    private fun beginRecording(recorder: Recorder, file: File, chat: String) {
        if (videoCapture == null) return
        val prepared = recorder.prepareRecording(this, FileOutputOptions.Builder(file).build())
        val withAudio = if (PermissionManager.hasRecordAudio(this)) {
            runCatching { prepared.withAudioEnabled() }.getOrDefault(prepared)
        } else {
            prepared
        }
        val started = runCatching { withAudio.start(ContextCompat.getMainExecutor(this)) { event -> onVideoEvent(event) } }
        val active = started.getOrNull()
        if (active == null) {
            AgentLog.add("MANOS no pude empezar a grabar: ${started.exceptionOrNull()?.message}")
            show("No pude empezar a grabar")
            endVideo()
            return
        }
        recording = active
        recordingStartedAt = SystemClock.elapsedRealtime()
        handler.postDelayed(stopVideoAtLimit, recordingLimitMs)
        feedback.vibrate(60)
        seen("🎥 grabando")
        val forWhom = if (chat.isEmpty()) "" else " para «$chat»"
        show(
            if (recordingLimitMs == MAX_VIDEO_MS) {
                "🎥 Grabando$forWhom… ✊ o ✋ quieta para terminar"
            } else {
                "🎥 Grabando$forWhom ${recordingLimitMs / 1000} segundos"
            },
        )
        AgentLog.add("MANOS grabando un video para «$chat»")
        publish(reason())
    }

    /** Mientras graba: ✊ otra vez termina (el primer puño no cuenta hasta bajar la mano). */
    private fun watchWhileRecording(result: HandAnalyzer.Result, now: Long) {
        val hand = result.hands.firstOrNull { it.presented } ?: result.hands.firstOrNull()
        val event = air.feed(hand?.sign, now, presented = hand?.presented ?: false, sure = hand?.sure ?: false)
            as? AirGestures.Event.Sign ?: return
        if (event.sign == HandSigns.Sign.FIST) {
            handler.post { stopVideo(SIGN_NAMES[event.sign] ?: event.sign.name) }
        }
    }

    private fun stopVideo(why: String) {
        val active = recording ?: return
        if (why != LIMIT && SystemClock.elapsedRealtime() - recordingStartedAt < MIN_VIDEO_MS) return
        handler.removeCallbacks(stopVideoAtLimit)
        AgentLog.add("MANOS termino el video ($why)")
        runCatching { active.stop() }
    }

    private fun onVideoEvent(event: VideoRecordEvent) {
        if (event !is VideoRecordEvent.Finalize) return
        val chat = recordingChat
        val file = recordingFile
        val seconds = (event.recordingStats.recordedDurationNanos / 1_000_000_000L).toInt()
        endVideo()
        if (file == null || !file.exists() || file.length() == 0L || seconds < 1) {
            AgentLog.add("MANOS el video no se grabó (error ${event.error}: ${event.cause?.message})")
            show("No se grabó el video")
            return
        }
        AgentLog.add("MANOS video listo: $seconds s, ${file.length() / 1024} KB")
        feedback.vibrate(40)
        val target = chat.orEmpty()
        val action = PendingAction(
            question = if (target.isEmpty()) {
                "¿Mando el video de $seconds segundos? Elegís el chat en WhatsApp."
            } else {
                "¿Le mando a «$target» el video de $seconds segundos?"
            },
            progress = "Mandando el video…",
            execute = { VideoMessage(applicationContext).send(file, target) },
        )
        // Con la escucha andando, lo pregunta en voz alta (contestás con la voz, 👍 o un guiño).
        if (!WakeWordService.confirm(action)) {
            answerSigns.reset()
            localPendingUntil = SystemClock.elapsedRealtime() + LOCAL_CONFIRM_MS
            localPending = action
            show("Video listo: 👍 para mandarlo, 👎 para no")
            evaluate()
        }
    }

    /** Termina la grabación: suelta la cámara de video, devuelve el micrófono y vuelve a mirar como siempre. */
    private fun endVideo() {
        recording = null
        recordingChat = null
        recordingFile = null
        handler.removeCallbacks(stopVideoAtLimit)
        videoCapture?.let { capture -> runCatching { provider?.unbind(capture) } }
        videoCapture = null
        WakeWordService.lendMicForVideo(false)
        useCase?.let { analysis -> runCatching { provider?.unbind(analysis) } }
        useCase = null
        cameraOn = false
        evaluate()
    }

    /** Sin la escucha andando: 👍 manda el video, 👎 no. */
    private fun answerVideo(action: PendingAction, result: HandAnalyzer.Result, now: Long) {
        if (now > localPendingUntil) {
            localPending = null
            show("No mandé el video")
            handler.post { evaluate() }
            return
        }
        val sign = result.hands.map { it.sign }
            .firstOrNull { it == HandSigns.Sign.THUMB_UP || it == HandSigns.Sign.THUMB_DOWN } ?: HandSigns.Sign.NONE
        val answer = answerSigns.feed(sign, now) ?: return
        localPending = null
        if (answer == HandSigns.Sign.THUMB_UP) {
            feedback.vibrate(40)
            show("👍 Mandando el video…")
            lifecycleScope.launch { show(action.execute()) }
        } else {
            show("👎 Listo, no lo mando.")
        }
        handler.post { evaluate() }
    }

    /** Sin el control de apps no hay gestos: lo prende solo si puede, o dice qué pasa y avisa. */
    private fun a11yMissing() {
        val state = A11yHealth.state(this)
        if (A11yHealth.repair(this, state)) {
            show("El control de apps se había apagado: lo vuelvo a prender")
            return
        }
        show(
            if (state == A11yHealth.State.NOT_RUNNING) {
                "El control de apps no anda: abrí ControlXIA para arreglarlo"
            } else {
                "Para deslizar necesito el control de apps (Accesibilidad)"
            },
        )
        A11yHealth.check(this, delayMs = 0)
    }

    /** "Me gusta": dos toques en el medio de la pantalla (reels, TikTok, Shorts). */
    private fun like() {
        val service = XiaAccessibilityService.instance ?: return a11yMissing()
        val metrics = resources.displayMetrics
        Gestures.doubleTapAt(service, metrics.widthPixels / 2f, metrics.heightPixels * 0.45f)
    }

    private fun onHandsUp() {
        AgentLog.add("MANOS levantaste las dos manos: pongo pausa y pregunto")
        seen("🙌 las dos manos")
        show("🙌 pausa")
        DeviceControl(this).media(DeviceControl.Media.PAUSE)
        WakeWordService.handsUp(this)
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, HandsWatchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, XiaApplication.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Gestos con las manos")
            .setContentText("Mano quieta y deslizá ⬆️⬇️⬅️➡️ · 👍 me gusta · ✋ pausa · ✌️ hablarme")
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Desactivar", stop)
            .build()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        recording?.let { runCatching { it.stop() } }
        recording = null
        if (videoCapture != null) {
            videoCapture?.let { capture -> runCatching { provider?.unbind(capture) } }
            videoCapture = null
            WakeWordService.lendMicForVideo(false)
        }
        runCatching { audio.unregisterAudioPlaybackCallback(playbackCallback) }
        XiaAccessibilityService.foregroundListeners -= foregroundListener
        runCatching { unregisterReceiver(screenReceiver) }
        useCase?.let { runCatching { provider?.unbind(it) } }
        useCase = null
        // Los modelos se cierran en el hilo del análisis, después del último cuadro.
        val closing = analyzer
        analyzer = null
        runCatching { analysisExecutor.execute { closing?.close() } }
        analysisExecutor.shutdown()
        cameraOn = false
        if (current?.get() === this) current = null
        publish("apagada: los gestos con la cámara no están andando")
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1003
        private const val ACTION_STOP = "com.controlxia.app.action.HANDS_STOP"

        /** Control lento por si se escapa algún aviso del sistema. */
        private const val HEARTBEAT_MS = 10_000L

        /** Después de tocar o escribir en el teléfono, un rato sin mirar señas. */
        private const val BUSY_QUIET_MS = 2_500L

        /** La palma hace "sigue" solo si un gesto pausó en estos minutos. */
        private const val RESUME_WINDOW_MS = 10 * 60_000L

        /** Videos con gestos: como mucho un minuto, y el primer segundo y medio no se puede cortar. */
        private const val MAX_VIDEO_MS = 60_000L
        private const val MIN_VIDEO_MS = 1_500L
        private const val MIC_HANDOFF_MS = 300L
        /** Si la cámara no puede mirar mientras graba, no hay gesto para cortar: un tope corto. */
        private const val BLIND_VIDEO_MS = 20_000L
        private const val LOCAL_CONFIRM_MS = 20_000L
        private const val LIMIT = "llegó al minuto"
        private const val IDLE_FRAME_MS = 300L
        private const val ACTIVE_FRAME_MS = 100L
        private const val ACTIVE_WINDOW_MS = 4_000L
        private const val MIN_CAMERA_FPS = 15
        private const val CAMERA_OFF_AFTER_MS = 8_000L
        private const val FRAMES_NEEDED = 4
        private const val COOLDOWN_MS = 8_000L

        /** Las señas quietas que hacen algo (las demás se ignoran). */
        /** Las señas que hacen algo (para mostrar la última que vio). */
        private val SHOWN = setOf(
            HandSigns.Sign.THUMB_UP, HandSigns.Sign.VICTORY, HandSigns.Sign.FIST,
            HandSigns.Sign.POINT_UP, HandSigns.Sign.POINT_DOWN, HandSigns.Sign.POINT_LEFT, HandSigns.Sign.POINT_RIGHT,
        )

        private val SIGN_NAMES = mapOf(
            HandSigns.Sign.POINT_UP to "☝️ dedo arriba", HandSigns.Sign.POINT_DOWN to "👇 dedo abajo",
            HandSigns.Sign.POINT_LEFT to "👈 dedo a la izquierda", HandSigns.Sign.POINT_RIGHT to "👉 dedo a la derecha",
            HandSigns.Sign.THUMB_UP to "👍 pulgar arriba", HandSigns.Sign.THUMB_DOWN to "👎 pulgar abajo",
            HandSigns.Sign.VICTORY to "✌️ V", HandSigns.Sign.OPEN_PALM to "✋ palma", HandSigns.Sign.FIST to "✊ puño",
        )

        @Volatile
        var running = false
            private set

        /** Lo que se ve en Ajustes: si la cámara mira, por qué, y la última seña que vio. */
        data class Live(val camera: Boolean = false, val reason: String = "", val seen: String? = null, val seenAt: Long = 0L)

        val live = MutableStateFlow(Live())

        /** La pantalla de prueba usa la cámara: el servicio la suelta mientras tanto. */
        @Volatile
        private var paused = false

        @Volatile
        private var current: WeakReference<HandsWatchService>? = null

        /** Cambió un ajuste de los gestos: aplicarlo ya (sin esperar el control lento). */
        fun refresh() {
            current?.get()?.let { service ->
                service.handler.post {
                    service.readSettings()
                    service.evaluate()
                }
            }
        }

        /** La pantalla de prueba con la cámara a la vista: el servicio suelta la cámara ([on]) o la retoma. */
        fun pause(on: Boolean) {
            paused = on
            current?.get()?.let { service -> service.handler.post { service.evaluate() } }
        }

        /** Arranca (con la app a la vista: si no, Android no da la cámara). */
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, HandsWatchService::class.java))
        }.isSuccess

        fun stop(context: Context) {
            context.stopService(Intent(context, HandsWatchService::class.java))
        }
    }
}
