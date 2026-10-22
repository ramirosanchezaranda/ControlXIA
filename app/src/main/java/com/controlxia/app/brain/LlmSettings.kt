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

    /**
     * Dejar que el modelo maneje las apps por su cuenta (leer la pantalla y
     * tocar, como antes). Apagado (por defecto) el modelo solo responde
     * preguntas o traduce el pedido a pasos que hace la app: con modelos
     * chicos es mucho más confiable.
     */
    var agentMode: Boolean
        get() = prefs.getBoolean(KEY_AGENT_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_AGENT_MODE, value).apply()

    /** Config resuelta del proveedor activo, o null si falta completar datos. */
    fun activeConfig(): LlmConfig? = config(activeProvider)

    /**
     * El proveedor activo y, detrás, con qué seguir si se queda sin cupo: los
     * otros modelos gratis del mismo proveedor (en Groq y Gemini cada modelo
     * tiene su propio cupo diario) y los demás proveedores con clave cargada.
     */
    fun chain(): List<LlmConfig> {
        val order = listOf(activeProvider) + LlmProviderType.entries.filter { it != activeProvider }
        return buildList {
            for (type in order) {
                val base = config(type) ?: continue
                add(base)
                if (type in FREE_PER_MODEL) {
                    type.suggestedModels.filter { it != base.model }.forEach { add(base.copy(model = it)) }
                }
            }
        }
    }

    /** Config resuelta de [type], o null si falta completar datos. */
    fun config(type: LlmProviderType): LlmConfig? {
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
        const val KEY_AGENT_MODE = "agent_mode"

        /** Proveedores con plan gratis y cupo separado por modelo. */
        val FREE_PER_MODEL = setOf(LlmProviderType.GROQ, LlmProviderType.GEMINI)

        /** Modelos que los proveedores apagaron y que pudieron quedar guardados. */
        val RETIRED_MODELS = setOf(
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
            "gemini-1.5-flash",
            "gemini-1.5-pro",
        )
    }
}
