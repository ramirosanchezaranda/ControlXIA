package com.controlxia.app.voice

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Los modelos de MediaPipe (Apache 2.0): el de gestos de las manos (8 MB) y
 * el de la cara (3,7 MB, para el guiño y mover la cabeza). Se baja desde la app la primera vez, como el modelo de voz, así el
 * APK no crece para quien no usa la función. Versión fija: si Google publica
 * otra, esta sigue disponible.
 */
object GestureModel {

    const val URL =
        "https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task"
    private const val SIZE = 8_373_440L
    const val FACE_URL =
        "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"
    private const val FACE_SIZE = 3_758_596L
    private const val TOTAL = SIZE + FACE_SIZE

    sealed interface State {
        data object Idle : State
        data class Downloading(val progress: Float) : State
        data object Done : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun file(context: Context): File = File(context.filesDir, "gesture_recognizer.task")

    fun isInstalled(context: Context): Boolean = file(context).length() == SIZE

    /** El de la cara (3,7 MB): guiño y cabeza para contestar sí o no. */
    fun faceFile(context: Context): File = File(context.filesDir, "face_landmarker.task")

    fun isFaceInstalled(context: Context): Boolean = faceFile(context).length() == FACE_SIZE

    /** Descarga los modelos que falten. [onReady] corre (en segundo plano) cuando están. */
    @Synchronized
    fun ensure(context: Context, onReady: () -> Unit = {}) {
        val app = context.applicationContext
        if (isInstalled(app) && isFaceInstalled(app)) {
            _state.value = State.Done
            onReady()
            return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                _state.value = State.Downloading(0f)
                if (!isInstalled(app)) download(URL, SIZE, file(app), 0f, SIZE.toFloat() / TOTAL)
                if (!isFaceInstalled(app)) download(FACE_URL, FACE_SIZE, faceFile(app), SIZE.toFloat() / TOTAL, 1f)
                _state.value = State.Done
                onReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed("No se pudo bajar el modelo de gestos: ${e.message}")
            }
        }
    }

    /** Baja [url] a [target]; el progreso va de [from] a [to] del total. */
    private fun download(url: String, size: Long, target: File, from: Float, to: Float) {
        val partial = File(target.parentFile, target.name + ".part")
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) error("el servidor respondió ${response.code}")
                val body = response.body ?: error("respuesta vacía")
                partial.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            total += n
                            _state.value = State.Downloading(from + (to - from) * (total.toFloat() / size).coerceAtMost(1f))
                        }
                    }
                }
            }
            if (partial.length() != size) error("se bajó incompleto")
            if (!partial.renameTo(target)) error("no se pudo guardar")
        } finally {
            partial.delete()
        }
    }
}
