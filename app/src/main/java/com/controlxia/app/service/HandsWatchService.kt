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
import com.controlxia.app.diagnostics.AgentLog
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
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * Gestos con la cámara frontal, como los gestos aéreos de los teléfonos:
 *
 * - **Deslizar en el aire** ([HandMotion]): dejás la mano quieta un instante y
 *   la movés rápido arriba, abajo o a un costado; la app hace el mismo
 *   deslizamiento con el dedo en la pantalla (reel siguiente o anterior, foto o
 *   historia). También con el índice apuntando ([HandSigns]).
 * - 👍 me gusta (dos toques en el video), ✋ sostenida pausa o sigue, ✌️ pausa
 *   y te escucha, 🙌 las dos manos pausan y preguntan "¿Qué necesitás?".
 * - Cuando el asistente pregunta algo: guiño o asentir = sí, negar = no, 👍/👎.
 * - Con el teléfono bloqueado y música sonando: deslizar a los costados cambia
 *   de tema, arriba y abajo el volumen.
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
    private val signs = HandSigns.Trigger(framesNeeded = 4, framesFor = mapOf(HandSigns.Sign.OPEN_PALM to 10))
    private val motion = HandMotion()
    private val feedback by lazy { Feedback(this) }

    @Volatile
    private var gesturesOn = true

    @Volatile
    private var always = false

    @Volatile
    private var confirmOn = true
    private val faceSigns = FaceSigns()
    private val answerSigns = HandSigns.Trigger(framesNeeded = 3, repeatable = emptySet())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            WakeWordSettings(this).handsPause = false
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        val started = runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type) }
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
        if (paused || !power.isInteractive) return false
        if (locked()) return musicPlaying() || (confirmOn && ConfirmState.waiting)
        return always || (confirmOn && ConfirmState.waiting) || musicPlaying() ||
            XiaAccessibilityService.foregroundPackage in MediaApps.VIDEO
    }

    /** Por qué la cámara está como está (para Ajustes). */
    private fun reason(): String = when {
        paused -> "en pausa: la está usando la prueba"
        !power.isInteractive -> "apagada: la pantalla está apagada"
        analyzer == null -> "apagada: falta el modelo de gestos (activá de nuevo «Señas con la cámara»)"
        confirmOn && ConfirmState.waiting -> "mirando: esperando tu sí o no"
        locked() && musicPlaying() -> "mirando: bloqueado con música"
        locked() -> "apagada: el teléfono está bloqueado y no suena nada"
        always -> "mirando: con cualquier app"
        musicPlaying() -> "mirando: se está reproduciendo algo"
        XiaAccessibilityService.foregroundPackage in MediaApps.VIDEO -> "mirando: app de videos adelante"
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
        // Para ver un deslizamiento hacen falta ~10 cuadros por segundo; que la cámara no saque 30.
        lowestFpsRange(cameras)?.let { range ->
            Camera2Interop.Extender(builder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
        }
        val analysis = builder.build()
        analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }
        val ok = runCatching {
            useCase?.let { cameras.unbind(it) }
            cameras.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }
        useCase = analysis.takeIf { ok.isSuccess }
        cameraOn = ok.isSuccess
        AgentLog.add(if (cameraOn) "MANOS cámara prendida (${reason()})" else "MANOS no pude usar la cámara: ${ok.exceptionOrNull()?.message}")
        publish(if (cameraOn) reason() else "no pude usar la cámara: ${ok.exceptionOrNull()?.message}")
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
        signs.reset()
        motion.reset()
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
                motion.reset()
                return
            }
            val result = engine.analyze(HandAnalyzer.upright(image))
            if (result.hands.isNotEmpty()) lastHandAt = now
            // Dos palmas abiertas levantadas: pausa y pregunta.
            streak = if (result.raised >= 2) streak + 1 else 0
            if (streak >= FRAMES_NEEDED && now - lastTrigger > COOLDOWN_MS) {
                streak = 0
                lastTrigger = now
                signs.reset()
                motion.reset()
                onHandsUp()
                return
            }
            if (!gesturesOn || result.raised >= 2) {
                motion.reset()
                signs.feed(HandSigns.Sign.NONE, now)
                return
            }
            // Deslizar en el aire (con la mano que se ve más).
            val hand = result.hands.firstOrNull()
            val wasArmed = motion.armed
            motion.feed(hand?.x, hand?.y, now)?.let { swipe ->
                signs.reset()
                act(swipe)
                return
            }
            if (motion.armed && !wasArmed) {
                // Un "tic" apenas: ya se puede deslizar.
                seen("✋ lista para deslizar")
                feedback.vibrate(12)
            }
            // Señas quietas: índice apuntando, pulgar, palma sostenida, V.
            val sign = hand?.sign?.takeIf { it in ACTIONS } ?: HandSigns.Sign.NONE
            if (sign != HandSigns.Sign.NONE && !motion.armed) seen(SIGN_NAMES[sign] ?: sign.name)
            signs.feed(sign, now)?.let { perform(it) }
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
            AgentLog.add("MANOS deslizar ${swipe.name}, pero el control de apps está apagado")
            show("Para deslizar necesito el control de apps (Accesibilidad)")
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

    /** Hace lo que pide la seña, en la app que esté adelante. */
    private fun perform(sign: HandSigns.Sign) {
        AgentLog.add("MANOS seña ${sign.name}")
        // El índice apuntando: como deslizar el dedo para ese lado.
        when (sign) {
            HandSigns.Sign.POINT_UP -> return act(HandMotion.Swipe.UP)
            HandSigns.Sign.POINT_DOWN -> return act(HandMotion.Swipe.DOWN)
            HandSigns.Sign.POINT_LEFT -> return act(HandMotion.Swipe.LEFT)
            HandSigns.Sign.POINT_RIGHT -> return act(HandMotion.Swipe.RIGHT)
            else -> Unit
        }
        feedback.vibrate(25)
        show(SIGN_NAMES[sign] ?: sign.name)
        when (sign) {
            HandSigns.Sign.THUMB_UP -> if (!locked()) like()
            HandSigns.Sign.OPEN_PALM -> DeviceControl(this).media(DeviceControl.Media.TOGGLE)
            HandSigns.Sign.VICTORY -> {
                DeviceControl(this).media(DeviceControl.Media.PAUSE)
                WakeWordService.listenNow(this)
            }
            else -> Unit
        }
    }

    /** "Me gusta": dos toques en el medio de la pantalla (reels, TikTok, Shorts). */
    private fun like() {
        val service = XiaAccessibilityService.instance ?: return
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
        private const val IDLE_FRAME_MS = 300L
        private const val ACTIVE_FRAME_MS = 100L
        private const val ACTIVE_WINDOW_MS = 4_000L
        private const val MIN_CAMERA_FPS = 15
        private const val CAMERA_OFF_AFTER_MS = 8_000L
        private const val FRAMES_NEEDED = 4
        private const val COOLDOWN_MS = 8_000L

        /** Las señas quietas que hacen algo (las demás se ignoran). */
        private val ACTIONS = setOf(
            HandSigns.Sign.POINT_UP, HandSigns.Sign.POINT_DOWN, HandSigns.Sign.POINT_LEFT,
            HandSigns.Sign.POINT_RIGHT, HandSigns.Sign.THUMB_UP, HandSigns.Sign.VICTORY,
            HandSigns.Sign.OPEN_PALM,
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
