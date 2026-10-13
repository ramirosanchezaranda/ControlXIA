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
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Descarga e instala el modelo de voz en español (39 MB) desde el sitio de
 * Vosk. Vive a nivel de app: la descarga sigue aunque se cambie de pantalla, y
 * la UI observa [state] para mostrar el progreso.
 */
object VoskModelDownloader {

    sealed interface State {
        data object Idle : State

        /** [progress] entre 0 y 1, o null si el servidor no informa el tamaño. */
        data class Downloading(val progress: Float?) : State
        data object Installing : State
        data object Done : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    // Sin tope de tiempo total: en datos móviles 39 MB pueden tardar minutos.
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Empieza la descarga (si no hay una en curso). [onInstalled] corre al terminar bien. */
    @Synchronized
    fun start(context: Context, onInstalled: () -> Unit = {}) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            val partial = File(app.cacheDir, "vosk-model.zip.part")
            try {
                _state.value = State.Downloading(0f)
                download(partial)
                _state.value = State.Installing
                VoskModelStore.install(app, partial)
                _state.value = State.Done
                onInstalled()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(friendlyError(e))
            } finally {
                partial.delete()
            }
        }
    }

    private fun download(target: File) {
        val request = Request.Builder().url(VoskModelStore.MODEL_URL).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Respuesta vacía")
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReported = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val percent = total?.let { (done * 100 / it).toInt() } ?: -1
                        if (percent != lastReported) {
                            lastReported = percent
                            _state.value = State.Downloading(total?.let { done.toFloat() / it })
                        }
                    }
                }
            }
        }
    }

    private fun friendlyError(e: Exception): String = when {
        e is UnknownHostException -> "Sin conexión a internet. Probá de nuevo."
        e.message?.contains("ENOSPC") == true -> "No hay espacio suficiente (hacen falta ~100 MB)."
        else -> "No se pudo descargar el modelo: ${e.message ?: e.javaClass.simpleName}"
    }
}
