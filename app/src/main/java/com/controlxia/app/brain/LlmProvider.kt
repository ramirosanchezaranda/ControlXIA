package com.controlxia.app.brain

/**
 * Abstracción de proveedor de LLM. El resto de la app solo habla con esta
 * interfaz: cambiar de proveedor es cuestión de configuración, no de código.
 */
interface LlmProvider {

    /**
     * Envía la conversación (más las tools disponibles) y devuelve la decisión
     * del modelo: texto para el usuario o una llamada a herramienta.
     * @throws LlmException si la API falla (key inválida, red, modelo inexistente).
     */
    suspend fun complete(
        messages: List<ChatMessage>,
        tools: List<ToolSpec> = emptyList(),
    ): LlmResponse
}

/**
 * Proveedores soportados, con sus modelos sugeridos. El usuario también puede
 * escribir a mano cualquier ID de modelo nuevo sin actualizar la app.
 */
enum class LlmProviderType(
    val displayName: String,
    val suggestedModels: List<String>,
    val needsBaseUrl: Boolean = false,
) {
    ANTHROPIC(
        displayName = "Anthropic (Claude)",
        suggestedModels = listOf("claude-haiku-4-5", "claude-sonnet-5"),
    ),
    OPENAI(
        displayName = "OpenAI (GPT)",
        suggestedModels = listOf("gpt-4o-mini", "gpt-4o"),
    ),
    GEMINI(
        displayName = "Google (Gemini)",
        suggestedModels = listOf("gemini-2.0-flash"),
    ),
    OPENAI_COMPATIBLE(
        displayName = "Compatible OpenAI (Groq, OpenRouter, Ollama…)",
        suggestedModels = emptyList(),
        needsBaseUrl = true,
    );
}

/** Configuración resuelta de un proveedor, lista para crear el cliente. */
data class LlmConfig(
    val type: LlmProviderType,
    val apiKey: String,
    val model: String,
    /** Solo para OPENAI_COMPATIBLE, ej: "https://api.groq.com/openai/v1". */
    val baseUrl: String? = null,
)

object LlmProviderFactory {

    fun create(config: LlmConfig): LlmProvider = when (config.type) {
        LlmProviderType.ANTHROPIC -> AnthropicProvider(config.apiKey, config.model)
        LlmProviderType.OPENAI -> OpenAiCompatibleProvider(
            apiKey = config.apiKey,
            model = config.model,
            baseUrl = "https://api.openai.com/v1",
        )
        LlmProviderType.GEMINI -> GeminiProvider(config.apiKey, config.model)
        LlmProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(
            apiKey = config.apiKey,
            model = config.model,
            baseUrl = requireNotNull(config.baseUrl) { "Falta la base URL del proveedor" },
        )
    }
}
