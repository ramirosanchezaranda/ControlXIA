package com.controlxia.app.voice

/**
 * Motor de palabra de activación ("wake word"). Corre on-device y llama a
 * [onDetected] cuando escucha la palabra. Abstrae el motor concreto (Porcupine
 * hoy; Vosk / openWakeWord podrían enchufarse detrás de esta misma interfaz).
 */
interface WakeWordEngine {

    /** Empieza a escuchar. [onDetected] se invoca en un hilo del motor. */
    fun start(onDetected: () -> Unit)

    /** Pausa la escucha y libera el micrófono (p. ej. para pasarle el mic al ASR). */
    fun stop()

    /** Libera todos los recursos nativos. Tras esto el motor no se reutiliza. */
    fun release()
}

/** Error de inicialización o ejecución del motor, con mensaje legible para la UI. */
class WakeWordException(message: String, cause: Throwable? = null) : Exception(message, cause)
