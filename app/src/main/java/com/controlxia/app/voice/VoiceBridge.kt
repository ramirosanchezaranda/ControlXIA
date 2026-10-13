package com.controlxia.app.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lo que una herramienta necesita del servicio de voz en medio de un pedido:
 * decir algo y esperar a que termine ("hablá después del tono"), y usar el
 * micrófono sin pelearse con el que escucha "pará". El servicio lo conecta al
 * arrancar; sin servicio, no hace nada.
 */
object VoiceBridge {

    /** Dice el texto y vuelve cuando terminó de hablar. */
    @Volatile
    var speaker: (suspend (String) -> Unit)? = null

    /** true: alguien necesita el micrófono (se deja de escuchar "pará"); false: lo devuelve. */
    @Volatile
    var micClaim: ((Boolean) -> Unit)? = null

    suspend fun say(text: String) {
        val speak = speaker ?: return
        withTimeoutOrNull(15_000) { speak(text) }
    }

    /** Usa el micrófono durante [block]. */
    suspend fun <T> withMicrophone(block: suspend () -> T): T {
        withContext(Dispatchers.Main) { micClaim?.invoke(true) }
        try {
            return block()
        } finally {
            withContext(Dispatchers.Main) { micClaim?.invoke(false) }
        }
    }
}
