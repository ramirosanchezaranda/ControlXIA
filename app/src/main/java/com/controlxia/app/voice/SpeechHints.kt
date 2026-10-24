package com.controlxia.app.voice

/**
 * Palabras que probablemente digas ahora, para que el reconocedor las prefiera
 * (Android 13+): el nombre del asistente, "IA", apps comunes y, si hay un
 * mensaje esperando el "sí", a quién va y sus palabras (así «cambiá guía por
 * IA» se entiende mejor).
 */
object SpeechHints {

    private val BASE = listOf(
        "IA", "WhatsApp", "Instagram", "Mercado Libre", "YouTube", "TikTok", "Spotify", "Telegram",
        "Gmail", "Google", "audio", "mensaje",
    )

    /** Lo de la conversación en curso (el contacto y las palabras del mensaje por mandar). */
    @Volatile
    var conversation: List<String> = emptyList()

    fun forMessage(text: String?, to: String?) {
        conversation = if (text == null) {
            emptyList()
        } else {
            listOfNotNull(to) + text.split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length >= 3 }.distinct().take(20)
        }
    }

    fun build(agentName: String): List<String> =
        (listOf(agentName) + BASE + conversation).filter { it.isNotBlank() }.distinct().take(MAX)

    private const val MAX = 50
}
