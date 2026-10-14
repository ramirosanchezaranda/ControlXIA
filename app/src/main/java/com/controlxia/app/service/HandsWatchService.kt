package com.controlxia.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Size
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
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.voice.AssistantState
import com.controlxia.app.voice.GestureModel
import com.controlxia.app.voice.HandSigns
import com.controlxia.app.voice.WakeWordSettings
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

/**
 * "Gestos con las manos": mientras mirás videos o reels, la cámara frontal
 * mira tus manos.
 * - Las dos manos arriba (palmas abiertas): pausa y el asistente pregunta
 *   "¿Qué necesitás?".
 * - Con una mano ([HandSigns]): dedo para arriba pasa al reel siguiente, para
 *   abajo vuelve al anterior, al costado pasa de foto o historia, pulgar arriba
 *   pone "me gusta" y la V pausa y te escucha.
 *
 * La cámara se prende solo mientras algo se reproduce o hay una app de videos
 * a la vista, y se apaga a los pocos segundos (Android muestra el punto verde
 * de cámara mientras está prendida). Todo se procesa en el teléfono: no se guardan ni se
 * mandan imágenes. Es opcional y se activa desde la app (Android solo deja usar
 * la cámara en segundo plano si el servicio arrancó con la app a la vista).
 */
class HandsWatchService : LifecycleService() {

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var recognizer: GestureRecognizer? = null
    private var provider: ProcessCameraProvider? = null
    private var cameraOn = false
    private var lastFrameAt = 0L
    private var streak = 0
    private var lastTrigger = 0L
    private val signs = HandSigns.Trigger()
    @Volatile
    private var gesturesOn = true

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
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        if (recognizer == null) loadRecognizer()
        if (provider == null) {
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({ provider = runCatching { future.get() }.getOrNull() }, ContextCompat.getMainExecutor(this))
            watchPlayback()
        }
        return START_NOT_STICKY
    }

    private fun loadRecognizer() {
        val model = GestureModel.file(this)
        if (!GestureModel.isInstalled(this)) {
            AgentLog.add("MANOS falta el modelo de gestos")
            return
        }
        recognizer = runCatching {
            val buffer = RandomAccessFile(model, "r").channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
            GestureRecognizer.createFromOptions(
                this,
                GestureRecognizer.GestureRecognizerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buffer).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumHands(2)
                    .setMinHandDetectionConfidence(0.5f)
                    .build(),
            )
        }.onFailure { AgentLog.add("MANOS no pude cargar el modelo: ${it.message}") }.getOrNull()
    }

    /** Prende la cámara mientras algo se reproduce; la apaga cuando para. */
    private fun watchPlayback() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        lifecycleScope.launch {
            var quietSince = 0L
            while (isActive) {
                gesturesOn = WakeWordSettings(this@HandsWatchService).handGestures
                // Reels sin sonido también cuentan: alcanza con que haya una app de videos adelante.
                val playing = runCatching { audio.isMusicActive }.getOrDefault(false) || videoAppInFront()
                val now = SystemClock.elapsedRealtime()
                if (playing) {
                    quietSince = 0L
                    if (!cameraOn) bindCamera()
                } else {
                    if (quietSince == 0L) quietSince = now
                    if (cameraOn && now - quietSince > CAMERA_OFF_AFTER_MS) unbindCamera()
                }
                delay(POLL_MS)
            }
        }
    }

    private fun videoAppInFront(): Boolean = runCatching {
        XiaAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString() in VIDEO_APPS
    }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun bindCamera() {
        val cameras = provider ?: return
        if (recognizer == null) return
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(480, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }
        val ok = runCatching {
            cameras.unbindAll()
            cameras.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }
        cameraOn = ok.isSuccess
        AgentLog.add(if (cameraOn) "MANOS cámara prendida (se está reproduciendo algo)" else "MANOS no pude usar la cámara: ${ok.exceptionOrNull()?.message}")
    }

    private fun unbindCamera() {
        runCatching { provider?.unbindAll() }
        cameraOn = false
        streak = 0
        signs.reset()
        AgentLog.add("MANOS cámara apagada (no se reproduce nada)")
    }

    private fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            // ~5 cuadros por segundo alcanzan y ahorran batería.
            if (now - lastFrameAt < FRAME_MS) return
            lastFrameAt = now
            // Mientras el asistente atiende, no hace falta mirar.
            if (AssistantState.state.value.phase != AssistantState.Phase.IDLE) {
                streak = 0
                return
            }
            val engine = recognizer ?: return
            val frame = upright(image.toBitmap(), image.imageInfo.rotationDegrees)
            val result = engine.recognize(BitmapImageBuilder(frame).build())
            // Dos palmas abiertas, con la muñeca en la parte de arriba de la imagen.
            val raised = result.gestures().indices.count { hand ->
                val top = result.gestures()[hand].firstOrNull() ?: return@count false
                val wristY = result.landmarks().getOrNull(hand)?.firstOrNull()?.y() ?: 1f
                top.categoryName() == "Open_Palm" && top.score() >= MIN_SCORE && wristY < MAX_WRIST_Y
            }
            streak = if (raised >= 2) streak + 1 else 0
            if (streak >= FRAMES_NEEDED && now - lastTrigger > COOLDOWN_MS) {
                streak = 0
                lastTrigger = now
                signs.reset()
                onHandsUp()
                return
            }
            // Con una mano: la seña más clara de las que se ven (con dos palmas arriba, nada).
            val sign = if (!gesturesOn || raised >= 2) {
                HandSigns.Sign.NONE
            } else {
                result.gestures().indices.map { hand ->
                    val top = result.gestures()[hand].firstOrNull()
                    val points = result.landmarks().getOrNull(hand).orEmpty().map { HandSigns.Point(it.x(), it.y()) }
                    HandSigns.classify(top?.categoryName(), top?.score() ?: 0f, points, frame.width.toFloat() / frame.height)
                }.firstOrNull { it in ACTIONS } ?: HandSigns.Sign.NONE
            }
            signs.feed(sign, now)?.let { perform(it) }
        } catch (e: Exception) {
            AgentLog.add("MANOS error al analizar: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun upright(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Hace lo que pide la seña, en la app que esté adelante. */
    private fun perform(sign: HandSigns.Sign) {
        AgentLog.add("MANOS seña ${sign.name}")
        vibrate()
        lifecycleScope.launch {
            val device = DeviceControl(this@HandsWatchService)
            when (sign) {
                HandSigns.Sign.POINT_UP -> device.scroll(DeviceControl.Direction.DOWN)
                HandSigns.Sign.POINT_DOWN -> device.scroll(DeviceControl.Direction.UP)
                HandSigns.Sign.POINT_LEFT -> device.scroll(DeviceControl.Direction.LEFT)
                HandSigns.Sign.POINT_RIGHT -> device.scroll(DeviceControl.Direction.RIGHT)
                HandSigns.Sign.THUMB_UP -> like()
                HandSigns.Sign.VICTORY -> {
                    device.media(DeviceControl.Media.PAUSE)
                    WakeWordService.listenNow(this@HandsWatchService)
                }
                else -> Unit
            }
        }
    }

    /** "Me gusta": dos toques en el medio de la pantalla (reels, TikTok, Shorts). */
    private fun like() {
        val service = XiaAccessibilityService.instance ?: return
        val metrics = resources.displayMetrics
        Gestures.doubleTapAt(service, metrics.widthPixels / 2f, metrics.heightPixels * 0.45f)
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun onHandsUp() {
        AgentLog.add("MANOS levantaste las dos manos: pongo pausa y pregunto")
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
            .setContentText("☝️ siguiente · 👇 anterior · 👍 me gusta · ✌️ hablarme · 🙌 pausa")
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Desactivar", stop)
            .build()
    }

    override fun onDestroy() {
        running = false
        runCatching { provider?.unbindAll() }
        analysisExecutor.shutdown()
        runCatching { recognizer?.close() }
        recognizer = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1003
        private const val ACTION_STOP = "com.controlxia.app.action.HANDS_STOP"
        private const val POLL_MS = 1_500L
        private const val CAMERA_OFF_AFTER_MS = 8_000L
        private const val FRAME_MS = 200L
        private const val FRAMES_NEEDED = 3
        private const val COOLDOWN_MS = 8_000L
        private const val MIN_SCORE = 0.55f
        private const val MAX_WRIST_Y = 0.8f

        /** Las señas que hacen algo (las demás se ignoran). */
        private val ACTIONS = setOf(
            HandSigns.Sign.POINT_UP, HandSigns.Sign.POINT_DOWN, HandSigns.Sign.POINT_LEFT,
            HandSigns.Sign.POINT_RIGHT, HandSigns.Sign.THUMB_UP, HandSigns.Sign.VICTORY,
        )

        /** Apps de videos: con una de estas adelante la cámara mira aunque no haya sonido. */
        private val VIDEO_APPS = setOf(
            "com.instagram.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
            "com.google.android.youtube", "com.facebook.katana", "com.netflix.mediaclient",
            "com.snapchat.android",
        )

        @Volatile
        var running = false
            private set

        /** Arranca (con la app a la vista: si no, Android no da la cámara). */
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, HandsWatchService::class.java))
        }.isSuccess

        fun stop(context: Context) {
            context.stopService(Intent(context, HandsWatchService::class.java))
        }
    }
}
