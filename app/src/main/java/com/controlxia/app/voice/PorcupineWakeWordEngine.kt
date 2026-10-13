package com.controlxia.app.voice

import android.content.Context
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback

/**
 * Motor de wake word basado en Picovoice Porcupine.
 *
 * Dos modos:
 *  - **Fábrica**: una palabra incluida ([WakeKeyword], en inglés).
 *  - **Custom**: un modelo `.ppn` propio (ej. "Xia") + el modelo de idioma
 *    español, ambos en assets. Ver docs/CUSTOM_WAKE_WORD.md.
 *
 * En ambos casos se necesita un AccessKey gratuito de Picovoice.
 * `PorcupineManager` administra su propio `AudioRecord`; el callback corre en
 * el hilo del motor.
 */
class PorcupineWakeWordEngine(
    private val context: Context,
    private val accessKey: String,
    private val keyword: WakeKeyword,
    private val sensitivity: Float,
    private val customKeywordAsset: String? = null,
    private val customModelAsset: String? = null,
) : WakeWordEngine {

    private var manager: PorcupineManager? = null

    // Callbacks vigentes: el manager se construye una sola vez, pero cada
    // start() puede traer callbacks nuevos.
    @Volatile private var detected: () -> Unit = {}
    @Volatile private var failed: (String) -> Unit = {}

    override fun start(onDetected: () -> Unit, onError: (String) -> Unit) {
        if (accessKey.isBlank()) {
            throw WakeWordException("Falta el AccessKey de Picovoice")
        }
        detected = onDetected
        failed = onError
        try {
            if (manager == null) {
                val callback = PorcupineManagerCallback { detected() }
                val builder = PorcupineManager.Builder()
                    .setAccessKey(accessKey)
                    .setSensitivity(sensitivity.coerceIn(0f, 1f))
                    .setErrorCallback { error ->
                        failed(error.message ?: "Se cortó la escucha del micrófono")
                    }
                if (customKeywordAsset != null) {
                    // Modelo entrenado propio (ej. "Xia") + modelo de idioma es.
                    builder.setKeywordPath(customKeywordAsset)
                    if (customModelAsset != null) builder.setModelPath(customModelAsset)
                } else {
                    builder.setKeyword(keyword.builtIn)
                }
                manager = builder.build(context, callback)
            }
            manager?.start()
        } catch (e: Exception) {
            release()
            throw WakeWordException(e.message ?: "No se pudo iniciar el wake word", e)
        }
    }

    override fun stop() {
        runCatching { manager?.stop() }
    }

    override fun release() {
        runCatching { manager?.stop() }
        runCatching { manager?.delete() }
        manager = null
    }
}
