package com.controlxia.app.voice

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * La pregunta que espera un "sí" o un "no" ("¿Le mando a Juan…?"), para poder
 * contestarla también con un gesto (guiño, cabeza, pulgar). null si no hay.
 */
object ConfirmState {

    private val _question = MutableStateFlow<String?>(null)
    val question: StateFlow<String?> = _question.asStateFlow()

    @Volatile
    private var askedAt = 0L

    /** Hay una pregunta reciente esperando (después de un minuto ya no se mira la cámara por ella). */
    val waiting: Boolean
        get() = _question.value != null && SystemClock.elapsedRealtime() - askedAt < WINDOW_MS

    fun set(question: String?) {
        askedAt = SystemClock.elapsedRealtime()
        _question.value = question
    }

    /** Terminó la conversación: ya no se contesta con gestos. */
    fun clear() = set(null)

    private const val WINDOW_MS = 60_000L
}
