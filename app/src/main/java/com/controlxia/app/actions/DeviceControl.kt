package com.controlxia.app.actions

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.service.XiaAccessibilityService
import com.controlxia.app.voice.ListeningOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

/**
 * Controles del teléfono que no pasan por ninguna app: botones del sistema
 * (atrás, inicio, notificaciones…), volumen, música y videos, linterna,
 * Wi-Fi, Bluetooth y brillo. Es lo que el Acceso por voz de Google permite
 * hacer con comandos sueltos.
 *
 * Android no deja a las apps prender el Wi-Fi o el Bluetooth por código desde
 * la versión 10: se abre el panel del sistema y se toca el interruptor con el
 * control de apps, como lo haría el usuario.
 */
class DeviceControl(private val context: Context) {

    // Las constantes nuevas se usan solo si [minSdk] lo permite (ver global()).
    @android.annotation.SuppressLint("InlinedApi")
    enum class Global(val action: Int, val minSdk: Int = 1) {
        BACK(AccessibilityService.GLOBAL_ACTION_BACK),
        HOME(AccessibilityService.GLOBAL_ACTION_HOME),
        RECENTS(AccessibilityService.GLOBAL_ACTION_RECENTS),
        NOTIFICATIONS(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),
        QUICK_SETTINGS(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS),
        POWER_MENU(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG),
        SPLIT_SCREEN(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN, Build.VERSION_CODES.N),
        LOCK(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, Build.VERSION_CODES.P),
        SCREENSHOT(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, Build.VERSION_CODES.P),
    }

    /** Botón del sistema. null si salió bien, o el motivo. */
    suspend fun global(which: Global): String? {
        if (Build.VERSION.SDK_INT < which.minSdk) return "Esta versión de Android no lo permite."
        val service = XiaAccessibilityService.instance ?: return NEEDS_A11Y
        if (which == Global.SCREENSHOT) {
            // Que la píldora del asistente no salga en la captura.
            ListeningOverlay.hideAll()
            delay(600)
        }
        val ok = service.performGlobalAction(which.action)
        AgentLog.add("TELÉFONO ${which.name} ${if (ok) "ok" else "falló"}")
        return if (ok) null else "No pude hacerlo."
    }

    enum class Direction { DOWN, UP, LEFT, RIGHT }

    /**
     * Desplaza la pantalla: la lista más grande si la hay, si no con el dedo.
     * Izquierda/derecha deslizan como para pasar de foto o de historia.
     */
    suspend fun scroll(direction: Direction): String? = withContext(Dispatchers.Default) {
        val service = XiaAccessibilityService.instance ?: return@withContext NEEDS_A11Y
        val metrics = context.resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val ok = when (direction) {
            Direction.DOWN, Direction.UP -> {
                val forward = direction == Direction.DOWN
                // En reels y videos cortos, "desplazar" de la accesibilidad a veces dice que
                // anduvo y no pasa de video: ahí, como el dedo.
                if (XiaAccessibilityService.foregroundPackage in MediaApps.VIDEO) {
                    return@withContext if (Gestures.flingVertical(service, up = forward)) null else "No pude desplazar la pantalla."
                }
                val target = service.rootInActiveWindow?.let { largestScrollable(it) }
                val action = if (forward) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                target?.performAction(action) == true || if (forward) {
                    Gestures.swipe(service, w / 2, h * 0.75f, w / 2, h * 0.30f, 350)
                } else {
                    Gestures.swipe(service, w / 2, h * 0.30f, w / 2, h * 0.75f, 350)
                }
            }
            // "Deslizá a la izquierda": el dedo va hacia la izquierda (la siguiente foto).
            Direction.LEFT -> Gestures.swipe(service, w * 0.85f, h / 2, w * 0.15f, h / 2, 250)
            Direction.RIGHT -> Gestures.swipe(service, w * 0.15f, h / 2, w * 0.85f, h / 2, 250)
        }
        if (ok) null else "No pude desplazar la pantalla."
    }

    private fun largestScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 40) return
            if (node.isScrollable && node.isVisibleToUser) {
                val r = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                val area = r.width() * r.height()
                if (area > bestArea) {
                    best = node
                    bestArea = area
                }
            }
            for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1)
        }
        visit(root, 0)
        return best
    }

    enum class Stream(val id: Int) {
        MEDIA(AudioManager.STREAM_MUSIC),
        RING(AudioManager.STREAM_RING),
        ALARM(AudioManager.STREAM_ALARM),
        CALL(AudioManager.STREAM_VOICE_CALL),
    }

    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Sube o baja el volumen [steps] pasos (negativo = bajar). */
    fun volumeStep(steps: Int, stream: Stream = Stream.MEDIA): String? = runCatching {
        val direction = if (steps >= 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(kotlin.math.abs(steps).coerceIn(1, 15)) {
            audio.adjustStreamVolume(stream.id, direction, if (it == 0) AudioManager.FLAG_SHOW_UI else 0)
        }
        null
    }.getOrElse { "No pude cambiar el volumen." }

    /** Volumen en [percent] % (0 = mínimo). */
    fun volumeSet(percent: Int, stream: Stream = Stream.MEDIA): String? = runCatching {
        val max = audio.getStreamMaxVolume(stream.id)
        val value = (max * percent.coerceIn(0, 100) / 100.0).let { kotlin.math.round(it).toInt() }
        audio.setStreamVolume(stream.id, value, AudioManager.FLAG_SHOW_UI)
        null
    }.getOrElse {
        // Bajar el timbre a 0 activa "no molestar" y eso pide un permiso especial.
        "No pude cambiar ese volumen (puede necesitar el permiso de No molestar)."
    }

    fun mute(on: Boolean, stream: Stream = Stream.MEDIA): String? = runCatching {
        val flag = if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        audio.adjustStreamVolume(stream.id, flag, AudioManager.FLAG_SHOW_UI)
        null
    }.getOrElse { "No pude silenciar." }

    enum class Media(val key: Int) {
        PLAY(KeyEvent.KEYCODE_MEDIA_PLAY),
        PAUSE(KeyEvent.KEYCODE_MEDIA_PAUSE),
        TOGGLE(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
        NEXT(KeyEvent.KEYCODE_MEDIA_NEXT),
        PREVIOUS(KeyEvent.KEYCODE_MEDIA_PREVIOUS),
        STOP(KeyEvent.KEYCODE_MEDIA_STOP),
    }

    /**
     * Música y videos (YouTube, Spotify…), como los botones de unos auriculares:
     * le llega a la app que está reproduciendo, sin permisos.
     */
    fun media(which: Media): String? = runCatching {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, which.key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, which.key))
        AgentLog.add("MULTIMEDIA ${which.name}")
        null
    }.getOrElse { "No pude controlar la reproducción." }

    fun isMediaPlaying(): Boolean = runCatching { audio.isMusicActive }.getOrDefault(false)

    /** Linterna. No necesita el permiso de cámara. */
    fun flashlight(on: Boolean): String? {
        val cameras = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = runCatching {
            cameras.cameraIdList.firstOrNull {
                cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: return "Este teléfono no tiene linterna."
        return runCatching {
            cameras.setTorchMode(id, on)
            torchOn = on
            null
        }.getOrElse { "No pude ${if (on) "prender" else "apagar"} la linterna (¿la está usando la cámara?)." }
    }

    fun toggleFlashlight(): String? = flashlight(!torchOn)

    enum class Toggle { WIFI, BLUETOOTH, AIRPLANE, MOBILE_DATA }

    /**
     * Prende o apaga Wi-Fi, Bluetooth, modo avión o datos: abre el ajuste del
     * sistema y toca su interruptor si no está como se pidió. null si salió bien.
     */
    suspend fun setToggle(which: Toggle, on: Boolean): String? = withContext(Dispatchers.Default) {
        val service = XiaAccessibilityService.instance ?: return@withContext NEEDS_A11Y
        val intent = when (which) {
            Toggle.WIFI -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent(Settings.Panel.ACTION_WIFI)
            } else {
                Intent(Settings.ACTION_WIFI_SETTINGS)
            }
            Toggle.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            Toggle.AIRPLANE -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            Toggle.MOBILE_DATA -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            } else {
                Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)
            }
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { withContext(Dispatchers.Main) { context.startActivity(intent) } }.isSuccess
        if (!opened) return@withContext "No pude abrir ese ajuste."
        val words = TOGGLE_WORDS.getValue(which)
        // El panel tarda en aparecer; se busca el interruptor por su texto.
        val switch = waitFor(4_000) { root -> findSwitch(root, words) }
            ?: return@withContext "Abrí el ajuste pero no encontré el interruptor: tocalo vos."
        if (switch.isChecked == on) {
            AgentLog.add("TELÉFONO ${which.name} ya estaba ${if (on) "prendido" else "apagado"}")
        } else {
            Nodes.click(service, switch)
            AgentLog.add("TELÉFONO ${which.name} → ${if (on) "prendido" else "apagado"}")
        }
        delay(600)
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        null
    }

    /**
     * Brillo en [percent] %: abre los ajustes rápidos y mueve la barra de brillo
     * con el control de apps (sin el permiso especial de modificar ajustes).
     */
    suspend fun brightness(percent: Int): String? = withContext(Dispatchers.Default) {
        val service = XiaAccessibilityService.instance ?: return@withContext NEEDS_A11Y
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        val slider = waitFor(3_000) { root -> findSlider(root) }
        if (slider == null) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return@withContext "No encontré la barra de brillo."
        }
        val range = slider.rangeInfo
        val value = range.min + (range.max - range.min) * percent.coerceIn(0, 100) / 100f
        val args = Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value)
        }
        val ok = slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args)
        delay(500)
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        if (ok) null else "No pude mover la barra de brillo."
    }

    /** Sube o baja el brillo un cuarto. */
    suspend fun brightnessStep(up: Boolean): String? {
        val current = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f * 100
        }.getOrDefault(50f)
        return brightness((current + if (up) 25 else -25).toInt())
    }

    /**
     * Prende o apaga un botón de los ajustes rápidos por su nombre ("no
     * molestar", "rotación automática", "ahorro de batería", "zona wi-fi"…).
     * Mira su estado antes, para no apagar lo que se pidió prender. Devuelve
     * null si salió bien, [TILE_NOT_FOUND] si no hay un botón con ese nombre, o
     * el motivo del fallo.
     */
    suspend fun quickTile(name: String, on: Boolean): String? = withContext(Dispatchers.Default) {
        val service = XiaAccessibilityService.instance ?: return@withContext NEEDS_A11Y
        val wanted = normalize(name).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (wanted.length < 3) return@withContext TILE_NOT_FOUND
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        delay(700)
        var tile: AccessibilityNodeInfo? = null
        // Los ajustes rápidos pueden tener varias páginas: se miran hasta tres.
        for (page in 0 until 3) {
            tile = waitFor(1_200) { root -> findTile(root, wanted) }
            if (tile != null) break
            val metrics = context.resources.displayMetrics
            Gestures.swipe(service, metrics.widthPixels * 0.85f, metrics.heightPixels * 0.3f, metrics.widthPixels * 0.15f, metrics.heightPixels * 0.3f, 250)
            delay(500)
        }
        if (tile == null) {
            closeShade(service)
            return@withContext TILE_NOT_FOUND
        }
        val state = tileState(tile)
        if (state == on) {
            AgentLog.add("TELÉFONO «$name» ya estaba ${if (on) "activado" else "desactivado"}")
        } else {
            Nodes.click(service, tile)
            AgentLog.add("TELÉFONO «$name» → ${if (on) "activado" else "desactivado"}")
        }
        delay(500)
        closeShade(service)
        null
    }

    private fun findTile(root: AccessibilityNodeInfo, wanted: String): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || found != null || depth > 30) return
            if (node.isVisibleToUser && (node.isClickable || node.isCheckable)) {
                val label = normalize("${node.text ?: ""} ${node.contentDescription ?: ""}")
                    .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ")
                if (wanted in label) {
                    found = node
                    return
                }
            }
            for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1)
        }
        visit(root, 0)
        return found
    }

    /** true/false si el botón dice si está activado; null si no se sabe. */
    private fun tileState(tile: AccessibilityNodeInfo): Boolean? {
        if (tile.isCheckable) return tile.isChecked
        val state = normalize(
            "${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.stateDescription ?: "" else ""} ${tile.contentDescription ?: ""}"
        )
        return when {
            Regex("\\b(desactivad|apagad|off)").containsMatchIn(state) -> false
            Regex("\\b(activad|encendid|prendid|on)\\b").containsMatchIn(state) -> true
            else -> null
        }
    }

    private fun closeShade(service: XiaAccessibilityService) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } else {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    /** Abre una pantalla de Ajustes por su nombre ("batería", "wifi", "sonido"…). */
    fun openSettings(which: String): String? {
        val key = normalize(which).trim()
        val action = SETTINGS_SCREENS.entries.firstOrNull { (words, _) -> words.any { it in key } }?.value
            ?: if (key.isBlank()) Settings.ACTION_SETTINGS else return "No conozco esos ajustes: decí «abrí ajustes» y «tocá» lo que busques."
        val intent = if (action == APP_DETAILS) {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
        } else {
            Intent(action)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); null }.getOrElse {
            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            null
        }
    }

    /** "Tenés 64 % de batería, cargando." */
    fun batteryStatus(): String {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val level = battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = battery.isCharging
        return "Tenés $level por ciento de batería" + if (charging) ", y está cargando." else "."
    }

    enum class Edit { COPY, PASTE, CUT, SELECT_ALL }

    /** Copiar, pegar, cortar o seleccionar todo en el campo de texto con el foco. */
    fun edit(action: Edit): String? {
        val root = XiaAccessibilityService.instance?.rootInActiveWindow ?: return NEEDS_A11Y
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: return "No hay ningún campo de texto con el cursor."
        val ok = when (action) {
            Edit.COPY -> field.performAction(AccessibilityNodeInfo.ACTION_COPY)
            Edit.PASTE -> field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Edit.CUT -> field.performAction(AccessibilityNodeInfo.ACTION_CUT)
            Edit.SELECT_ALL -> {
                val length = field.text?.length ?: 0
                field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
                })
            }
        }
        return if (ok) null else "No pude hacerlo en ese campo."
    }

    /** Vuelve a la app que se estaba usando antes (dos toques en "recientes"). */
    suspend fun previousApp(): String? {
        val service = XiaAccessibilityService.instance ?: return NEEDS_A11Y
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        delay(350)
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        return null
    }

    private suspend fun waitFor(
        timeoutMs: Long,
        find: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?,
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val roots = XiaAccessibilityService.instance?.windows?.mapNotNull { it.root }.orEmpty() +
                listOfNotNull(XiaAccessibilityService.instance?.rootInActiveWindow)
            roots.firstNotNullOfOrNull(find)?.let { return it }
            delay(250)
        }
        return null
    }

    /** El interruptor cuyo texto, o el de su fila, nombra el ajuste. */
    private fun findSwitch(root: AccessibilityNodeInfo, words: List<String>): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun matches(node: AccessibilityNodeInfo?): Boolean {
            var current = node
            repeat(3) {
                val text = normalize("${(current?.text ?: "")} ${(current?.contentDescription ?: "")}")
                if (words.any { it in text }) return true
                current = current?.parent ?: return false
            }
            return false
        }
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || found != null || depth > 30) return
            if (node.isCheckable && node.isVisibleToUser && matches(node)) {
                found = node
                return
            }
            for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1)
        }
        visit(root, 0)
        if (found != null) return found
        // Paneles con un solo interruptor (Wi-Fi): el primero que haya.
        fun first(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > 30) return null
            if (node.isCheckable && node.isVisibleToUser && node.className?.contains("Switch") == true) return node
            for (i in 0 until node.childCount) first(node.getChild(i), depth + 1)?.let { return it }
            return null
        }
        return first(root, 0)
    }

    private fun findSlider(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var any: AccessibilityNodeInfo? = null
        fun visit(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > 30) return null
            if (node.rangeInfo != null && node.isVisibleToUser) {
                val text = normalize("${(node.text ?: "")} ${(node.contentDescription ?: "")} ${node.viewIdResourceName.orEmpty()}")
                if ("brill" in text || "bright" in text) return node
                if (any == null) any = node
            }
            for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1)?.let { return it }
            return null
        }
        return visit(root, 0) ?: any
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale("es")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    companion object {
        const val NEEDS_A11Y = "Para eso necesito el control de apps activado (ControlXIA → Sistema)."
        const val TILE_NOT_FOUND = "no-tile"
        private const val APP_DETAILS = "app_details"

        /** Pantallas de Ajustes por lo que se dice. */
        private val SETTINGS_SCREENS = linkedMapOf(
            listOf("wifi", "wi fi", "internet") to Settings.ACTION_WIFI_SETTINGS,
            listOf("bluetooth") to Settings.ACTION_BLUETOOTH_SETTINGS,
            listOf("pantalla", "brillo") to Settings.ACTION_DISPLAY_SETTINGS,
            listOf("sonido", "volumen", "tono") to Settings.ACTION_SOUND_SETTINGS,
            listOf("bateria", "carga") to Intent.ACTION_POWER_USAGE_SUMMARY,
            listOf("aplicacion", "apps") to Settings.ACTION_APPLICATION_SETTINGS,
            listOf("ubicacion", "gps") to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            listOf("almacenamiento", "memoria", "espacio") to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            listOf("seguridad", "bloqueo", "huella", "contrasena") to Settings.ACTION_SECURITY_SETTINGS,
            listOf("privacidad", "permiso") to Settings.ACTION_PRIVACY_SETTINGS,
            listOf("accesibilidad") to Settings.ACTION_ACCESSIBILITY_SETTINGS,
            listOf("fecha", "hora") to Settings.ACTION_DATE_SETTINGS,
            listOf("idioma") to Settings.ACTION_LOCALE_SETTINGS,
            listOf("teclado") to Settings.ACTION_INPUT_METHOD_SETTINGS,
            listOf("datos", "uso de datos") to Settings.ACTION_DATA_ROAMING_SETTINGS,
            listOf("nfc", "pago") to Settings.ACTION_NFC_SETTINGS,
            listOf("no molestar") to Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS,
            listOf("red", "redes", "conexion", "avion") to Settings.ACTION_WIRELESS_SETTINGS,
            listOf("informacion", "acerca", "telefono") to Settings.ACTION_DEVICE_INFO_SETTINGS,
            listOf("cuenta") to Settings.ACTION_SYNC_SETTINGS,
            listOf("esta app", "controlxia", "asistente") to APP_DETAILS,
        )

        /** Lo último que se pidió: el sistema no dice si la linterna está prendida. */
        @Volatile
        private var torchOn = false

        private val TOGGLE_WORDS = mapOf(
            Toggle.WIFI to listOf("wi-fi", "wifi", "wi fi"),
            Toggle.BLUETOOTH to listOf("bluetooth"),
            Toggle.AIRPLANE to listOf("avion", "airplane", "vuelo"),
            Toggle.MOBILE_DATA to listOf("datos", "mobile data", "data"),
        )
    }
}
