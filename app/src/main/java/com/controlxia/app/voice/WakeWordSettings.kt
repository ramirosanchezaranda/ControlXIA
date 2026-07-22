package com.controlxia.app.voice

import android.content.Context
import android.content.SharedPreferences
import ai.picovoice.porcupine.Porcupine
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Palabras de activación de fábrica disponibles sin entrenar un modelo custom.
 * "Xia" propio requiere entrenar un .ppn en la consola de Picovoice — queda
 * para una iteración siguiente. Mientras tanto se usa una de estas.
 */
enum class WakeKeyword(val builtIn: Porcupine.BuiltInKeyword, val label: String) {
    JARVIS(Porcupine.BuiltInKeyword.JARVIS, "Jarvis"),
    COMPUTER(Porcupine.BuiltInKeyword.COMPUTER, "Computer"),
    BUMBLEBEE(Porcupine.BuiltInKeyword.BUMBLEBEE, "Bumblebee"),
    PICOVOICE(Porcupine.BuiltInKeyword.PICOVOICE, "Picovoice");

    companion object {
        fun fromName(name: String?): WakeKeyword =
            entries.firstOrNull { it.name == name } ?: JARVIS
    }
}

/**
 * Configuración del wake word, persistida cifrada en el dispositivo. El AccessKey
 * de Picovoice es obligatorio (gratuito) incluso para palabras de fábrica.
 */
class WakeWordSettings(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "wakeword_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var accessKey: String
        get() = prefs.getString(KEY_ACCESS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ACCESS, value).apply()

    var keyword: WakeKeyword
        get() = WakeKeyword.fromName(prefs.getString(KEY_KEYWORD, null))
        set(value) = prefs.edit().putString(KEY_KEYWORD, value.name).apply()

    /** 0f = menos sensible (menos falsos positivos), 1f = más sensible. */
    var sensitivity: Float
        get() = prefs.getFloat(KEY_SENS, 0.5f)
        set(value) = prefs.edit().putFloat(KEY_SENS, value.coerceIn(0f, 1f)).apply()

    /** true cuando el motor tiene lo mínimo para arrancar (el AccessKey). */
    fun isReady(): Boolean = accessKey.isNotBlank()

    private companion object {
        const val KEY_ACCESS = "access_key"
        const val KEY_KEYWORD = "keyword"
        const val KEY_SENS = "sensitivity"
    }
}
