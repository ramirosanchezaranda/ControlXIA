package com.controlxia.app.actions

import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Display
import androidx.annotation.RequiresApi
import com.controlxia.app.diagnostics.AgentLog
import com.controlxia.app.service.XiaAccessibilityService
import com.controlxia.app.voice.VoiceBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Capturas de pantalla que la app guarda ella misma (Imágenes/ControlXIA),
 * para poder anotarlas en una nota. Con Android 11+ las saca el control de
 * apps sin mostrar nada; antes, con la captura del sistema (queda en la
 * galería, sin saber el nombre del archivo). La burbuja del asistente se
 * esconde mientras tanto.
 */
object Screenshots {

    /** Dónde quedó la captura: [where] para decir o anotar; [uri] si la guardó la app. */
    class Shot(val where: String, val uri: Uri?)

    suspend fun capture(context: Context, name: String = "captura"): Shot? {
        val service = XiaAccessibilityService.instance ?: return null
        return VoiceBridge.withoutOverlay {
            delay(OVERLAY_GONE_MS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                take(service)?.let { bitmap -> save(context, bitmap, name) }
            } else {
                null
            } ?: systemScreenshot(service)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun take(service: AccessibilityService): Bitmap? = suspendCancellableCoroutine { cont ->
        runCatching {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val bitmap = runCatching {
                            Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                        runCatching { result.hardwareBuffer.close() }
                        if (cont.isActive) cont.resume(bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        AgentLog.add("CAPTURA el sistema no la dejó sacar (código $errorCode)")
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        }.onFailure { if (cont.isActive) cont.resume(null) }
    }

    private suspend fun save(context: Context, bitmap: Bitmap, name: String): Shot? = withContext(Dispatchers.IO) {
        val file = "${safeName(name)} ${STAMP.format(Date())}.png"
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, file)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
                resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                Shot("Imágenes/$FOLDER/$file", uri)
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), FOLDER).apply { mkdirs() }
                val out = File(dir, file)
                out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Shot(out.path, Uri.fromFile(out))
            }
        }.onFailure { AgentLog.add("CAPTURA no pude guardarla: ${it.message}") }.getOrNull()
    }

    /** La captura del sistema: queda en la galería (sin saber el nombre del archivo). */
    private suspend fun systemScreenshot(service: AccessibilityService): Shot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
        delay(SYSTEM_SHOT_MS)
        return if (ok) Shot("la galería (Capturas de pantalla)", null) else null
    }

    private fun safeName(name: String) = name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(40).ifBlank { "captura" }

    private val STAMP = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US)
    const val FOLDER = "ControlXIA"
    private const val OVERLAY_GONE_MS = 250L
    private const val SYSTEM_SHOT_MS = 1_500L
}
