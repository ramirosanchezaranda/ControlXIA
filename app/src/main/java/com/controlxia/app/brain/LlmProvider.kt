package com.controlxia.app.brain

/**
 * Abstracción de proveedor de LLM. El resto de la app solo habla con esta
 * interfaz: cambiar de proveedor es cuestión de configuración, no de código.
 */
interface LlmProvider {

    /**
     * Si se pueden recortar resultados viejos del agente (pantallas ya leídas)
     * para ahorrar tokens. Anthropic exige reenviar el historial del bucle sin
     * cambios (el razonamiento queda atado a él); los demás lo permiten.
     */
    val canCompactSteps: Boolean get() = true

    /**
     * Envía la conversación, las tools disponibles y los pasos del agente ya
     * hechos en este pedido ([steps]: herramientas pedidas y sus resultados), y
     * devuelve la decisión del modelo: texto para el usuario o más herramientas.
     * @throws LlmException si la API falla (key inválida, red, modelo inexistente).
     */
    suspend fun complete(
        messages: List<ChatMessage>,
        tools: List<ToolSpec> = emptyList(),
        steps: List<AgentStep> = emptyList(),
    ): LlmResponse
}

/**
 * Proveedores soportados, con sus modelos sugeridos (el primero es el que se
 * usa si no se eligió ninguno). El usuario también puede escribir a mano
 * cualquier ID de modelo nuevo sin actualizar la app.
 */
enum class LlmProviderType(
    val displayName: String,
    val suggestedModels: List<String>,
    val needsBaseUrl: Boolean = false,
    /** URL fija para proveedores compatibles con OpenAI conocidos. */
    val fixedBaseUrl: String? = null,
) {
    ANTHROPIC(
        displayName = "Anthropic (Claude)",
        // Haiku 5.5 es el más rápido y barato: buena opción si la respuesta tarda.
        suggestedModels = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"),
    ),
    GROQ(
        displayName = "Groq",
        // gpt-oss-120b: el más capaz de Groq para usar herramientas en varios
        // pasos. gpt-oss-20b: el más rápido. Qwen 3.8: preview, llama varias
        // herramientas a la vez.
        suggestedModels = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.8-27b"),
        fixedBaseUrl = "https://api.groq.com/openai/v1",
    ),
    OPENAI(
        displayName = "OpenAI (GPT)",
        suggestedModels = listOf("gpt-5.4-mini", "gpt-5.5", "gpt-4o-mini"),
        fixedBaseUrl = "https://api.openai.com/v1",
    ),
    GEMINI(
        displayName = "Google (Gemini)",
        // gemini-2.0-flash se apagó en junio de 2026.
        suggestedModels = listOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.5-flash"),
    ),
    OPENAI_COMPATIBLE(
        displayName = "Compatible OpenAI (OpenRouter, Ollama…)",
        suggestedModels = emptyList(),
        needsBaseUrl = true,
    );
}

/** Configuración resuelta de un proveedor, lista para crear el cliente. */
data class LlmConfig(
    val type: LlmProviderType,
    val apiKey: String,
    val model: String,
    /** Solo para OPENAI_COMPATIBLE, ej: "https://openrouter.ai/api/v1". */
    val baseUrl: String? = null,
)

object LlmProviderFactory {

    fun create(config: LlmConfig): LlmProvider = when (config.type) {
        LlmProviderType.ANTHROPIC -> AnthropicProvider(config.apiKey, config.model)
        LlmProviderType.GEMINI -> GeminiProvider(config.apiKey, config.model)
        LlmProviderType.GROQ, LlmProviderType.OPENAI -> OpenAiCompatibleProvider(
            apiKey = config.apiKey,
            model = config.model,
            baseUrl = requireNotNull(config.type.fixedBaseUrl),
        )
        LlmProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(
            apiKey = config.apiKey,
            model = config.model,
            baseUrl = requireNotNull(config.baseUrl) { "Falta la base URL del proveedor" },
        )
    }
}
