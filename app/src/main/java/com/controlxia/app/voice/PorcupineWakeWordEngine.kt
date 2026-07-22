package com.controlxia.app.voice

import android.content.Context
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback

/**
 * Motor de wake word basado en Picovoice Porcupine. Usa una palabra de fábrica
 * ([WakeKeyword]) y requiere un AccessKey gratuito de Picovoice.
 *
 * `PorcupineManager` administra su propio `AudioRecord` en un hilo interno; el
 * callback de detección corre en ese hilo.
 */
class PorcupineWakeWordEngine(
    private val context: Context,
    private val accessKey: String,
    private val keyword: WakeKeyword,
    private val sensitivity: Float,
) : WakeWordEngine {

    private var manager: PorcupineManager? = null

    override fun start(onDetected: () -> Unit) {
        if (accessKey.isBlank()) {
            throw WakeWordException("Falta el AccessKey de Picovoice")
        }
        try {
            if (manager == null) {
                val callback = PorcupineManagerCallback { onDetected() }
                manager = PorcupineManager.Builder()
                    .setAccessKey(accessKey)
                    .setKeyword(keyword.builtIn)
                    .setSensitivity(sensitivity.coerceIn(0f, 1f))
                    .build(context, callback)
            }
            manager?.start()
        } catch (e: Exception) {
            release()
            throw WakeWordException(e.message ?: "No se pudo iniciar el wake word", e)
        }
    }

    override fun stop() {
        // stop() pausa la captura sin destruir el modelo, así reanudar es barato.
        runCatching { manager?.stop() }
    }

    override fun release() {
        runCatching { manager?.stop() }
        runCatching { manager?.delete() }
        manager = null
    }
}
