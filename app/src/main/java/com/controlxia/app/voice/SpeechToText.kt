package com.controlxia.app.voice

/**
 * Reconocimiento de voz (voz → texto) para transcribir el comando que sigue al
 * wake word. Abstrae la implementación (hoy el `SpeechRecognizer` de Android;
 * mañana Whisper on-device podría ir detrás de esta misma interfaz).
 */
interface SpeechToText {

    /**
     * Escucha un pedido y transcribe. Los callbacks pueden llegar en el main
     * thread. [onPartial] es opcional (texto provisional mientras habla el
     * usuario); [onResult] entrega la transcripción final; [onError] un mensaje
     * y si fue porque no se dijo nada (silencio o ruido sin palabras), que en
     * modo conversación no es un error sino el fin de la charla.
     */
    fun listen(
        onPartial: (String) -> Unit = {},
        onResult: (String) -> Unit,
        onError: (message: String, noSpeech: Boolean) -> Unit,
        options: ListenOptions = ListenOptions(),
    )

    /** Corta la escucha en curso y libera recursos. */
    fun cancel()
}

/** Ajustes de una escucha. */
class ListenOptions(
    /** Palabras que probablemente diga (nombres, "IA"): el reconocedor las prefiere (Android 13+). */
    val hints: List<String> = emptyList(),
    /**
     * Para respuestas cortas: si lo entendido hasta ahora ya es una respuesta
     * completa ("sí", "no"), cuántos ms sin cambios esperar antes de darla por
     * terminada, sin esperar el silencio largo. null = esperar como siempre.
     */
    val quickAnswer: ((String) -> Long?)? = null,
    /** El micrófono ya está escuchando: para que el tono avise justo cuando se puede hablar. */
    val onReady: (() -> Unit)? = null,
)
