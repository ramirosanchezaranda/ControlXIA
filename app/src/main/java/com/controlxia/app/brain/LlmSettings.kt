package com.controlxia.app.brain

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Configuración del LLM persistida cifrada en el dispositivo
 * (EncryptedSharedPreferences). Guarda una API key, modelo y base URL por
 * proveedor, más cuál es el proveedor activo.
 */
class LlmSettings(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "llm_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var activeProvider: LlmProviderType
        get() = prefs.getString(KEY_ACTIVE, null)
            ?.let { runCatching { LlmProviderType.valueOf(it) }.getOrNull() }
            ?: LlmProviderType.ANTHROPIC
        set(value) = prefs.edit().putString(KEY_ACTIVE, value.name).apply()

    fun apiKey(type: LlmProviderType): String =
        prefs.getString("${type.name}_api_key", "").orEmpty()

    fun setApiKey(type: LlmProviderType, key: String) =
        prefs.edit().putString("${type.name}_api_key", key).apply()

    fun model(type: LlmProviderType): String =
        prefs.getString("${type.name}_model", null)
            ?: type.suggestedModels.firstOrNull().orEmpty()

    fun setModel(type: LlmProviderType, model: String) =
        prefs.edit().putString("${type.name}_model", model).apply()

    fun baseUrl(type: LlmProviderType): String =
        prefs.getString("${type.name}_base_url", "").orEmpty()

    fun setBaseUrl(type: LlmProviderType, url: String) =
        prefs.edit().putString("${type.name}_base_url", url).apply()

    /** Config resuelta del proveedor activo, o null si falta completar datos. */
    fun activeConfig(): LlmConfig? {
        val type = activeProvider
        val model = model(type)
        val key = apiKey(type)
        val baseUrl = baseUrl(type).ifBlank { null }
        if (model.isBlank()) return null
        if (key.isBlank() && type != LlmProviderType.OPENAI_COMPATIBLE) return null
        if (type.needsBaseUrl && baseUrl == null) return null
        return LlmConfig(type = type, apiKey = key, model = model, baseUrl = baseUrl)
    }

    private companion object {
        const val KEY_ACTIVE = "active_provider"
    }
}
