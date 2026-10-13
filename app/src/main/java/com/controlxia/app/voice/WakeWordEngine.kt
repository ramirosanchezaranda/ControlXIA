package com.controlxia.app.voice

/**
 * Motor de palabra de activación ("wake word"). Corre on-device y llama a
 * [start]'s `onDetected` cuando escucha la palabra. Abstrae el motor concreto
 * (Vosk hoy; openWakeWord podría enchufarse detrás de esta misma interfaz).
 */
interface WakeWordEngine {

    /**
     * Empieza a escuchar sin bloquear (la primera vez carga el modelo de voz).
     * Todos los callbacks llegan en un hilo del motor:
     * - [onReady]: ya está escuchando.
     * - [onDetected]: escuchó la palabra; el motor deja de escuchar solo.
     * - [onError]: no pudo arrancar o se cayó. `transient` = vale la pena
     *   reintentar más tarde (micrófono ocupado), a diferencia de un error de
     *   configuración (palabra fuera del vocabulario).
     */
    fun start(
        onDetected: () -> Unit,
        onError: (message: String, transient: Boolean) -> Unit = { _, _ -> },
        onReady: () -> Unit = {},
    )

    /** Deja de escuchar y libera el micrófono (p. ej. para pasárselo al ASR). */
    fun stop()

    /** Libera todos los recursos. Tras esto el motor no se reutiliza. */
    fun release()
}
