package com.controlxia.app.brain

import android.content.Context
import android.content.SharedPreferences
import com.controlxia.app.SecurePrefs

/**
 * Configuración del LLM persistida cifrada en el dispositivo
 * (EncryptedSharedPreferences). Guarda una API key, modelo y base URL por
 * proveedor, más cuál es el proveedor activo.
 */
class LlmSettings(context: Context) {

    private val prefs: SharedPreferences by lazy {
        SecurePrefs.get(context, "llm_settings")
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

    /**
     * Modelo guardado, o el primero sugerido. Si el guardado es uno que el
     * proveedor ya dio de baja (ej. gemini-2.0-flash), devuelve el sugerido
     * para que la app no quede respondiendo "modelo inexistente".
     */
    fun model(type: LlmProviderType): String {
        val saved = prefs.getString("${type.name}_model", null)
        val fallback = type.suggestedModels.firstOrNull().orEmpty()
        return when {
            saved == null -> fallback
            saved in RETIRED_MODELS -> fallback
            else -> saved
        }
    }

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

        /** Modelos que los proveedores apagaron y que pudieron quedar guardados. */
        val RETIRED_MODELS = setOf(
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
            "gemini-1.5-flash",
            "gemini-1.5-pro",
        )
    }
}
