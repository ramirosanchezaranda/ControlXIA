package com.controlxia.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech

/**
 * Preferencias de la voz de Xia (no sensibles, sin cifrar): voz elegida del
 * motor TTS, tono (pitch) y velocidad. Se aplican al `TextToSpeech` con
 * [applyTo] antes de hablar, así los cambios toman efecto sin reiniciar nada.
 */
class VoiceSettings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("voice_settings", Context.MODE_PRIVATE)

    /** Nombre de la voz del motor TTS, o null para la voz por defecto del idioma. */
    var voiceName: String?
        get() = prefs.getString(KEY_VOICE, null)
        set(value) = prefs.edit().putString(KEY_VOICE, value).apply()

    /** 0.5 = grave, 1.0 = normal, 2.0 = agudo. */
    var pitch: Float
        get() = prefs.getFloat(KEY_PITCH, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_PITCH, value.coerceIn(0.5f, 2.0f)).apply()

    /** 0.5 = lento, 1.0 = normal, 2.0 = rápido. */
    var rate: Float
        get() = prefs.getFloat(KEY_RATE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_RATE, value.coerceIn(0.5f, 2.0f)).apply()

    /** Aplica tono, velocidad y voz elegida a un motor TTS ya inicializado. */
    fun applyTo(tts: TextToSpeech) {
        tts.setPitch(pitch)
        tts.setSpeechRate(rate)
        val name = voiceName ?: return
        runCatching {
            tts.voices?.firstOrNull { it.name == name }?.let { tts.voice = it }
        }
    }

    companion object {
        private const val KEY_VOICE = "voice_name"
        private const val KEY_PITCH = "pitch"
        private const val KEY_RATE = "rate"

        /** Adivina el género a partir del nombre de la voz (best-effort). */
        fun guessGender(voiceName: String): String? {
            val n = voiceName.lowercase()
            return when {
                Regex("female|-f-|_f_|#f|mujer|helena|monica|paulina|sabina|marisol|laura|lucia|elvira|conchita|penelope")
                    .containsMatchIn(n) -> "Femenina"
                Regex("\\bmale\\b|-m-|_m_|#m|hombre|pablo|jorge|diego|miguel|carlos|enrique|raul|andres")
                    .containsMatchIn(n) -> "Masculina"
                else -> null
            }
        }
    }
}
