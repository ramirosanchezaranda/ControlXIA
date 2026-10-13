package com.controlxia.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * ASR con el `SpeechRecognizer` nativo de Android, en español rioplatense.
 * Debe crearse y usarse en el main thread, por eso todo el trabajo se postea
 * al looper principal (el wake word detecta en un hilo del motor).
 */
class AndroidSpeechToText(private val context: Context) : SpeechToText {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    override fun listen(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        main.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onError("No hay reconocimiento de voz disponible en este equipo")
                return@post
            }
            cancelInternal()
            val sr = try {
                SpeechRecognizer.createSpeechRecognizer(context)
            } catch (e: Exception) {
                onError("No se pudo iniciar el reconocimiento de voz")
                return@post
            }
            recognizer = sr
            // Algunos reconocedores llaman onError después de onResults (o dos
            // veces onError). Solo cuenta el primer final de cada escucha.
            var finished = false
            fun finish(block: () -> Unit) {
                if (finished || recognizer !== sr) return
                finished = true
                block()
                cancelInternal()
            }
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    finish {
                        if (text.isBlank()) onError("No se entendió el comando")
                        else onResult(text)
                    }
                }

                override fun onPartialResults(partial: Bundle?) {
                    if (finished || recognizer !== sr) return
                    partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?.let(onPartial)
                }

                override fun onError(error: Int) {
                    finish { onError(errorMessage(error)) }
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            try {
                sr.startListening(buildIntent())
            } catch (e: Exception) {
                finish { onError("No se pudo iniciar el reconocimiento de voz") }
            }
        }
    }

    override fun cancel() {
        main.post { cancelInternal() }
    }

    private fun cancelInternal() {
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "No se entendió el comando"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No escuché nada"
        SpeechRecognizer.ERROR_AUDIO -> "Error de audio"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Falta permiso de micrófono"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Error de red en el reconocimiento"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "El reconocedor está ocupado"
        else -> "Error de reconocimiento ($error)"
    }
}
