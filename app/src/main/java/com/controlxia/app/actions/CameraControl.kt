package com.controlxia.app.actions

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.permissions.PermissionManager
import com.controlxia.app.service.XiaAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

/**
 * La cámara del teléfono por voz: abrirla (foto, video o selfie), sacar una
 * foto, empezar y terminar un video y cambiar de cámara. Usa la app de cámara
 * del teléfono y toca su botón con el control de apps, así las fotos y videos
 * quedan en la galería como siempre.
 *
 * Mientras se graba, otra app usa el micrófono y Android le da silencio a las
 * demás, salvo a un servicio de accesibilidad que tenga una ventana a la vista
 * ("controlar una grabación de video con comandos de voz", según la
 * documentación de Android). Por eso durante la grabación se muestra un aviso
 * del servicio de accesibilidad, y el asistente escucha "terminá el video".
 */
class CameraControl(private val context: Context) {

    enum class Mode { PHOTO, VIDEO, SELFIE }

    /** Abre la cámara en [mode]. null si salió bien. */
    suspend fun open(mode: Mode): String? = withContext(Dispatchers.Default) {
        if (!PermissionManager.canLaunchActivities(context)) {
            return@withContext "Para abrir la cámara necesito el permiso Mostrar sobre otras apps."
        }
        val locked = (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        val intent = when (mode) {
            Mode.VIDEO -> Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA)
            // Bloqueado: la versión "segura" abre la cámara sobre el bloqueo.
            Mode.PHOTO -> Intent(
                if (locked) MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
            )
            Mode.SELFIE -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                // Cada fabricante entiende uno distinto; ninguno está garantizado.
                putExtra("android.intent.extras.CAMERA_FACING", 1)
                putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                putExtra("com.google.assistant.extra.USE_FRONT_CAMERA", true)
            }
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        cameraPackageFor(intent).takeIf { it.isNotEmpty() }?.let { cameraPackages += it }
        val opened = runCatching { withContext(Dispatchers.Main) { context.startActivity(intent) } }.isSuccess
        if (!opened) return@withContext "No encontré una app de cámara."
        AgentLog.add("CÁMARA abrir ${mode.name}")
        if (waitForCamera(5_000) == null) return@withContext "Abrí la cámara pero tarda en aparecer."
        null
    }

    /** Saca una foto (abre la cámara si no está en pantalla). */
    suspend fun takePhoto(selfie: Boolean = false): String? {
        if (selfie || cameraRoot() == null) open(if (selfie) Mode.SELFIE else Mode.PHOTO)?.let { return it }
        delay(PREVIEW_MS)
        return pressShutter(Purpose.PHOTO)
    }

    /** Empieza a grabar un video. */
    suspend fun startVideo(): String? {
        open(Mode.VIDEO)?.let { return it }
        delay(PREVIEW_MS)
        pressShutter(Purpose.START_VIDEO)?.let { return it }
        setRecording(true)
        return null
    }

    /** Termina el video que se está grabando. */
    suspend fun stopVideo(): String? {
        if (cameraRoot() == null && !recording) return "No hay ningún video grabándose."
        val result = pressShutter(Purpose.STOP_VIDEO)
        setRecording(false)
        return result
    }

    /** Cambia entre la cámara de atrás y la de adelante. */
    suspend fun switchCamera(): String? = withContext(Dispatchers.Default) {
        val service = XiaAccessibilityService.instance ?: return@withContext DeviceControl.NEEDS_A11Y
        val root = cameraRoot() ?: return@withContext "La cámara no está abierta."
        val button = best(root) { node, text, id ->
            if (SWITCH.containsMatchIn(text) || SWITCH.containsMatchIn(id)) 5 else 0
        } ?: return@withContext "No encontré el botón para cambiar de cámara."
        click(service, button)
        AgentLog.add("CÁMARA cambiar de cámara")
        null
    }

    private enum class Purpose { PHOTO, START_VIDEO, STOP_VIDEO }

    /**
     * Toca el botón de la cámara. Se busca por su nombre o id ("obturador",
     * "shutter", "grabar") y por estar abajo al centro; si la app no lo
     * nombra, se toca ese lugar.
     */
    private suspend fun pressShutter(purpose: Purpose): String? = withContext(Dispatchers.Default) {
        val service = XiaAccessibilityService.instance ?: return@withContext DeviceControl.NEEDS_A11Y
        val root = waitForCamera(3_000) ?: return@withContext "La cámara no está en pantalla."
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val wanted = when (purpose) {
            Purpose.PHOTO -> PHOTO_WORDS
            Purpose.START_VIDEO -> START_WORDS
            Purpose.STOP_VIDEO -> STOP_WORDS
        }
        val button = best(root) { node, text, id ->
            var score = 0
            if ("shutter" in id || "obturador" in text || "shutter" in text) score += 5
            if ("capture" in id || "record" in id) score += 3
            if (wanted.containsMatchIn(text)) score += 4
            if (AVOID.containsMatchIn(text) || AVOID.containsMatchIn(id)) score -= 8
            val box = Rect().also { node.getBoundsInScreen(it) }
            val centered = if (width > height) {
                box.centerX() > width * 0.75 // apaisado: el botón queda a un costado
            } else {
                kotlin.math.abs(box.centerX() - width / 2) < width * 0.15 && box.centerY() > height * 0.6
            }
            if (centered) score += 3
            if (box.width() > width * 0.12) score += 1
            score
        }
        val ok = if (button != null) {
            AgentLog.add("CÁMARA tocar «${button.contentDescription ?: button.viewIdResourceName ?: "botón"}»")
            click(service, button)
        } else {
            AgentLog.add("CÁMARA no encontré el botón: toco abajo al centro")
            if (width > height) {
                Gestures.tapAt(service, width * 0.9f, height / 2f)
            } else {
                Gestures.tapAt(service, width / 2f, height * 0.86f)
            }
        }
        if (!ok) return@withContext "No pude tocar el botón de la cámara."
        null
    }

    /** El elemento tocable con mejor puntaje (al menos 3), o null. */
    private fun best(
        root: AccessibilityNodeInfo,
        score: (node: AccessibilityNodeInfo, text: String, id: String) -> Int,
    ): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = 2
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 40) return
            if (node.isVisibleToUser && (node.isClickable || node.isLongClickable)) {
                val text = normalize("${(node.contentDescription ?: "")} ${(node.text ?: "")}")
                val id = node.viewIdResourceName.orEmpty().lowercase(Locale.ROOT)
                val value = score(node, text, id)
                if (value > bestScore) {
                    best = node
                    bestScore = value
                }
            }
            for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1)
        }
        visit(root, 0)
        return best
    }

    private fun click(service: XiaAccessibilityService, node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK) || Gestures.tap(service, node)

    private suspend fun waitForCamera(timeoutMs: Long): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            cameraRoot()?.let { return it }
            delay(250)
        }
        return null
    }

    /** La ventana de la cámara, si está en pantalla. */
    private fun cameraRoot(): AccessibilityNodeInfo? {
        val root = XiaAccessibilityService.instance?.rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: return null
        return root.takeIf { pkg in cameraPackages || "camera" in pkg.lowercase(Locale.ROOT) }
    }

    private fun cameraPackageFor(intent: Intent): String =
        context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
            ?.takeIf { it != "android" }
            .orEmpty()

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale("es")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    companion object {
        /** Frases para terminar un video, que el asistente escucha sin internet mientras se graba. */
        val STOP_VIDEO_PHRASES = listOf(
            "terminá el video", "terminar video", "detené el video", "detené la grabación",
            "cortá el video", "pará de grabar", "pará el video", "frená el video",
        )

        /** Si hay un video grabándose (lo empezó el asistente). */
        @Volatile
        var recording = false
            private set

        /** El servicio de voz se entera para escuchar "terminá el video". */
        @Volatile
        var onRecordingChanged: ((Boolean) -> Unit)? = null

        private val cameraPackages: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        private const val PREVIEW_MS = 1_200L

        private val PHOTO_WORDS = Regex("(tomar|sacar|capturar|hacer) (una )?foto|take (a )?photo|capture|foto")
        private val START_WORDS = Regex("grabar|grabacion|iniciar|empezar|record|start")
        private val STOP_WORDS = Regex("detener|parar|finalizar|terminar|stop|grabando|recording")
        private val AVOID = Regex(
            "pausa|pause|galeria|gallery|miniatura|thumbnail|cambiar|switch|flip|girar|flash|" +
                "ajuste|setting|modo|mode|filtro|filter|zoom|temporizador|timer|hdr|beauty|belleza"
        )
        private val SWITCH = Regex("cambiar|girar|switch|flip|frontal|trasera|delantera|selfie|toggle")

        private val main = Handler(Looper.getMainLooper())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        // Débil: la vista pertenece a la ventana del servicio de accesibilidad.
        private var indicator: java.lang.ref.WeakReference<View>? = null

        private fun setRecording(on: Boolean) {
            if (recording == on) return
            recording = on
            AgentLog.add(if (on) "CÁMARA grabando" else "CÁMARA terminé el video")
            main.post { if (on) showIndicator() else hideIndicator() }
            onRecordingChanged?.invoke(on)
        }

        /**
         * Aviso "● Grabando" del servicio de accesibilidad: además de mostrar el
         * estado, hace que Android le pase el micrófono al asistente mientras la
         * cámara graba. Tocarlo termina el video.
         */
        private fun showIndicator() {
            val service = XiaAccessibilityService.instance ?: return
            if (indicator?.get() != null) return
            val density = service.resources.displayMetrics.density
            val chip = TextView(service).apply {
                text = "●  Grabando · decí «terminá el video» o tocá acá"
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 18 * density
                    setColor(Color.parseColor("#E6C62828"))
                }
                setOnClickListener {
                    scope.launch { CameraControl(service).stopVideo() }
                }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (48 * density).toInt()
            }
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            runCatching {
                wm.addView(chip, params)
                indicator = java.lang.ref.WeakReference(chip)
            }.onFailure { AgentLog.add("CÁMARA no pude mostrar el aviso de grabación: ${it.message}") }
        }

        private fun hideIndicator() {
            val view = indicator?.get() ?: return
            indicator = null
            runCatching { (view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view) }
        }
    }
}
