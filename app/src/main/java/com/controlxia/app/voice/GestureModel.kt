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
 * El modelo de gestos de MediaPipe (8 MB, Apache 2.0) para "pausa con las
 * manos". Se baja desde la app la primera vez, como el modelo de voz, así el
 * APK no crece para quien no usa la función. Versión fija: si Google publica
 * otra, esta sigue disponible.
 */
object GestureModel {

    const val URL =
        "https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task"
    private const val SIZE = 8_373_440L

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

    /** Descarga el modelo si falta. [onReady] corre (en segundo plano) cuando está. */
    @Synchronized
    fun ensure(context: Context, onReady: () -> Unit = {}) {
        val app = context.applicationContext
        if (isInstalled(app)) {
            _state.value = State.Done
            onReady()
            return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            val partial = File(app.cacheDir, "gesture_recognizer.task.part")
            try {
                _state.value = State.Downloading(0f)
                val request = Request.Builder().url(URL).build()
                client.newCall(request).execute().use { response ->
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
                                _state.value = State.Downloading((total.toFloat() / SIZE).coerceAtMost(1f))
                            }
                        }
                    }
                }
                if (partial.length() != SIZE) error("se bajó incompleto")
                if (!partial.renameTo(file(app))) error("no se pudo guardar")
                _state.value = State.Done
                onReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                partial.delete()
                _state.value = State.Failed("No se pudo bajar el modelo de gestos: ${e.message}")
            }
        }
    }
}
