package com.controlxia.app.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler

/**
 * Palabra de activación con Vosk, 100 % en el teléfono y sin cuentas.
 *
 * Lee el micrófono en un hilo propio y le pasa el audio a
 * [WakePhraseDetector], que decide si se dijo el nombre. Antes, el hilo carga
 * el modelo de voz ([VoskModelStore]) y traduce el nombre elegido a palabras
 * que el modelo conoce ([WakePhraseResolver]).
 *
 * Mientras el asistente trabaja o habla, el servicio usa otra instancia con
 * [commands] ("pará", "basta"…): avisa cuál se dijo con [onCommand]. Con
 * [echoCancel] se le pide al teléfono que no se oiga a sí mismo.
 */
class VoskWakeWordEngine(
    context: Context,
    private val phrase: String,
    private val sensitivity: Float,
    private val commands: List<String> = emptyList(),
    private val echoCancel: Boolean = false,
    private val onCommand: (String) -> Unit = {},
) : WakeWordEngine {

    private val appContext = context.applicationContext

    @Volatile
    private var listener: Listener? = null

    override fun start(
        onDetected: () -> Unit,
        onError: (message: String, transient: Boolean) -> Unit,
        onReady: () -> Unit,
    ) {
        if (listener?.isAlive == true) return
        listener = Listener(onDetected, onError, onReady).also { it.start() }
    }

    override fun stop() {
        val current = listener ?: return
        listener = null
        current.finish()
        if (Thread.currentThread() !== current) runCatching { current.join(JOIN_TIMEOUT_MS) }
    }

    override fun release() = stop()

    private inner class Listener(
        private val onDetected: () -> Unit,
        private val onError: (String, Boolean) -> Unit,
        private val onReady: () -> Unit,
    ) : Thread("xia-wakeword") {

        @Volatile
        private var active = true

        fun finish() {
            active = false
        }

        /** Reporta un error una sola vez y solo si nadie pidió parar. */
        private fun fail(message: String, transient: Boolean) {
            if (!active) return
            active = false
            onError(message, transient)
        }

        // El servicio verifica el permiso de micrófono antes de arrancar.
        @SuppressLint("MissingPermission")
        override fun run() {
            VoskModelStore.enter()
            var recorder: AudioRecord? = null
            var echo: AcousticEchoCanceler? = null
            var detector: WakePhraseDetector? = null
            try {
                if (phrase.isBlank()) return fail("Falta el nombre del asistente", false)
                val model = try {
                    VoskModelStore.get(appContext)
                } catch (e: Exception) {
                    return fail(e.message ?: "No se pudo cargar el modelo de voz", false)
                }
                if (!active) return

                val words = WakePhraseResolver.resolve(phrase) { VoskModelStore.hasWord(model, it) }
                    ?: return fail(
                        "No reconozco “$phrase”. En Ajustar, escribí cómo suena (ej. «sia»)",
                        false,
                    )

                // Las palabras de frenar que el modelo no conoce se omiten.
                val stops = commands
                    .mapNotNull { word ->
                        WakePhraseResolver.resolve(word) { VoskModelStore.hasWord(model, it) }
                            ?.let { word to it }
                    }
                    .filter { it.second != words }
                    .distinctBy { it.second }
                val phrases = listOf(WakePhraseDetector.Phrase(words)) +
                    stops.map { WakePhraseDetector.Phrase(it.second, alone = true) }
                detector = WakePhraseDetector(model, phrases, sensitivity)
                recorder = openRecorder() ?: return fail("No se pudo abrir el micrófono", true)
                if (echoCancel && AcousticEchoCanceler.isAvailable()) {
                    echo = runCatching { AcousticEchoCanceler.create(recorder.audioSessionId) }.getOrNull()
                    runCatching { echo?.enabled = true }
                }
                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    return fail("El micrófono está ocupado por otra app", true)
                }
                onReady()

                val chunk = ShortArray(WakePhraseDetector.SAMPLE_RATE / 10) // 100 ms
                while (active) {
                    val n = recorder.read(chunk, 0, chunk.size)
                    if (n < 0) return fail("Se cortó la lectura del micrófono ($n)", true)
                    val index = if (n > 0) detector.detect(chunk, n) else -1
                    if (index >= 0) {
                        active = false // suelta el micrófono al salir del bucle
                        if (index == 0) onDetected() else onCommand(stops[index - 1].first)
                    }
                }
            } catch (e: SecurityException) {
                fail("Falta el permiso de micrófono", false)
            } catch (e: Exception) {
                fail(e.message ?: "Se cortó la escucha", true)
            } finally {
                runCatching { echo?.release() }
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                runCatching { detector?.close() }
                VoskModelStore.exit()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord? {
        val rate = WakePhraseDetector.SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null
        // ~1 s de buffer: absorbe la pausa de la verificación sin perder audio.
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, rate * 2),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }
        return recorder
    }

    private companion object {
        const val JOIN_TIMEOUT_MS = 1_500L
    }
}
