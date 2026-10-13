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
 * Un modelo sugerido y lo que conviene saber para elegirlo. Los costos van en
 * comparación ("20 veces Haiku") y no en dólares, que cambian seguido; los
 * precios de referencia están en docs/AGENT.md.
 */
data class ModelOption(
    val id: String,
    /** Etiqueta corta: "Recomendado", "Más rápido"… */
    val tag: String,
    /** Para qué sirve, en una línea. */
    val note: String,
)

/**
 * Proveedores soportados, con sus modelos sugeridos de mejor a peor opción
 * para el asistente (el primero es el que se usa si no se eligió ninguno). El
 * usuario también puede escribir a mano cualquier ID de modelo nuevo sin
 * actualizar la app. Revisado en octubre de 2026.
 */
enum class LlmProviderType(
    val displayName: String,
    /** Qué ofrece, en una línea: gratis o pago. */
    val summary: String,
    val models: List<ModelOption>,
    val needsBaseUrl: Boolean = false,
    /** URL fija para proveedores compatibles con OpenAI conocidos. */
    val fixedBaseUrl: String? = null,
) {
    ANTHROPIC(
        displayName = "Anthropic (Claude)",
        summary = "Pago por uso",
        models = listOf(
            ModelOption(
                "claude-haiku-5-5", "Recomendado · el más eficiente",
                "Rápido y muy barato. Si se traba en pedidos largos dentro de apps, probá Sonnet.",
            ),
            ModelOption(
                "claude-sonnet-5-5", "Más confiable",
                "Mejor para pedidos largos dentro de apps; cuesta unas 20 veces lo que Haiku.",
            ),
            ModelOption(
                "claude-opus-5-5", "Más capaz",
                "Más lento y caro: para un asistente de voz casi nunca hace falta.",
            ),
        ),
    ),
    GROQ(
        displayName = "Groq",
        summary = "Gratis: 8.000 tokens por minuto y 1.000 pedidos por día",
        models = listOf(
            ModelOption("openai/gpt-oss-120b", "Recomendado · gratis", "El más confiable de Groq para manejar el teléfono."),
            ModelOption("qwen/qwen3.8-27b", "Alternativa · gratis", "Mismo límite gratis; en preview, puede cambiar."),
            ModelOption("openai/gpt-oss-20b", "Más rápido · gratis", "Se saltea pasos en pedidos de varias cosas."),
        ),
        fixedBaseUrl = "https://api.groq.com/openai/v1",
    ),
    OPENAI(
        displayName = "OpenAI (GPT)",
        summary = "Pago por uso",
        models = listOf(
            ModelOption(
                "gpt-6-luna", "Recomendado · el más eficiente",
                "Rápido y muy barato. Si se traba en pedidos largos, probá Sol.",
            ),
            ModelOption("gpt-6-sol", "Más confiable", "Mejor para pedidos largos; cuesta unas 20 veces lo que Luna."),
            ModelOption("gpt-5.4-mini", "Generación anterior", "Si tu cuenta todavía no tiene GPT-6."),
        ),
        fixedBaseUrl = "https://api.openai.com/v1",
    ),
    GEMINI(
        displayName = "Google (Gemini)",
        summary = "Uso gratis con límite diario (clave de Google AI Studio)",
        models = listOf(
            ModelOption("gemini-3.8-flash", "Recomendado · gratis", "El Flash más nuevo y capaz."),
            ModelOption("gemini-3.5-flash-lite", "Más liviano · gratis", "Más rápido; puede fallar en pedidos de varios pasos."),
            ModelOption("gemini-3.1-flash-lite", "El más barato · gratis", "Para pedidos simples."),
        ),
    ),
    OPENAI_COMPATIBLE(
        displayName = "Compatible OpenAI (OpenRouter, Ollama…)",
        summary = "Cualquier servicio con la API de OpenAI",
        models = emptyList(),
        needsBaseUrl = true,
    );

    val suggestedModels: List<String> get() = models.map { it.id }

    fun option(model: String): ModelOption? = models.firstOrNull { it.id == model.trim() }
}

/** Una combinación proveedor + modelo recomendada, para elegir con un toque. */
data class Recommendation(
    val title: String,
    val detail: String,
    val type: LlmProviderType,
    val model: String,
)

/** Lo mejor para el asistente, gratis y pago. */
val RECOMMENDED = listOf(
    Recommendation("Groq · gpt-oss-120b", "Gratis. El que mejor anda sin pagar.", LlmProviderType.GROQ, "openai/gpt-oss-120b"),
    Recommendation("Gemini 3.8 Flash", "Gratis con límite diario.", LlmProviderType.GEMINI, "gemini-3.8-flash"),
    Recommendation(
        "Claude Haiku 5.5", "Pago, el más eficiente: centavos por día de uso.",
        LlmProviderType.ANTHROPIC, "claude-haiku-5-5",
    ),
    Recommendation("GPT-6 Luna", "Pago, el más eficiente de OpenAI.", LlmProviderType.OPENAI, "gpt-6-luna"),
)

/**
 * Qué decir del modelo elegido: su nota si es uno sugerido, o un aviso si
 * parece chico para manejar el teléfono (se saltea pasos o dice haber hecho
 * cosas sin hacerlas). null si no hay nada que decir.
 */
fun modelHint(type: LlmProviderType, model: String): String? {
    type.option(model)?.let { return "${it.tag}: ${it.note}" }
    val id = model.lowercase()
    return when {
        "gpt-oss-20b" in id -> "Rápido, pero en pedidos de varias cosas se saltea pasos."
        // 1b a 9b: "llama-3.1-8b-instant", "qwen3-4b" (no "qwen3.8-27b").
        Regex("(?<![0-9.])[1-9]b(?![a-z0-9])").containsMatchIn(id) ->
            "Modelo chico: sirve para charlar, pero falla al manejar apps. Probá uno más grande."
        else -> null
    }
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
