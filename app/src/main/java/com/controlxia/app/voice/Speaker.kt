package com.controlxia.app.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * La voz del asistente: el TTS del teléfono en español (es-AR si está), con la
 * voz y el tono elegidos. Avisa con [onFinished] cada vez que termina una
 * frase y deja esperar a que termine ([speakAndWait]). Lo que se pide antes
 * de que el TTS esté listo queda en espera. Todo pasa en el hilo principal.
 */
class Speaker(context: Context, private val onFinished: (id: String?) -> Unit = {}) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = TextToSpeech(appContext) { status -> main.post { onInit(status) } }

    /** El TTS ya puede hablar. */
    var ready = false
        private set

    /** Frase pedida antes de que el TTS termine de inicializar. */
    private var pending: Pair<String, String>? = null

    /** Quién espera que termine de hablar una frase (por id de la frase). */
    private val waiters = mutableMapOf<String, () -> Unit>()

    /** Dice [text] (corta lo que estuviera diciendo). [id] vuelve en [onFinished]. */
    fun speak(text: String, id: String, queueIfNotReady: Boolean = true) {
        val engine = tts
        if (engine == null || !ready) {
            if (queueIfNotReady) pending = text to id
            return
        }
        VoiceSettings(appContext).applyTo(engine)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /** Dice [text] y vuelve cuando terminó (enseguida si no hay TTS). [before] corre justo antes. */
    suspend fun speakAndWait(text: String, before: () -> Unit = {}) = suspendCancellableCoroutine<Unit> { cont ->
        main.post {
            if (!ready) {
                if (cont.isActive) cont.resume(Unit)
                return@post
            }
            val id = "wait_${SystemClock.uptimeMillis()}"
            waiters[id] = { if (cont.isActive) cont.resume(Unit) }
            before()
            speak(text, id)
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        applySpanish(engine)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                main.post { finished(utteranceId) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { finished(utteranceId) }
            }
        })
        ready = true
        pending?.let { (text, id) ->
            pending = null
            speak(text, id)
        }
    }

    private fun finished(id: String?) {
        waiters.remove(id)?.invoke()
        onFinished(id)
    }

    /** es-AR si está instalado; si no, el español más cercano disponible. */
    private fun applySpanish(engine: TextToSpeech) {
        val candidates = listOf(Locale("es", "AR"), Locale("es", "US"), Locale("es", "ES"), Locale("es"))
        for (locale in candidates) {
            val result = runCatching { engine.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) return
        }
    }
}
