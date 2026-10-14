package com.controlxia.app.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Estado en vivo de la interacción de voz, compartido entre el servicio (que lo
 * actualiza) y la pantalla del asistente sobre el bloqueo (que lo observa).
 */
object AssistantState {

    enum class Phase { IDLE, LISTENING, HEARD, REPLYING }

    data class Ui(
        val phase: Phase = Phase.IDLE,
        val agentName: String = "Xia",
        val status: String = "",
        val locked: Boolean = false,
    )

    private val _state = MutableStateFlow(Ui())
    val state: StateFlow<Ui> = _state.asStateFlow()

    private val _level = MutableStateFlow(0f)

    /** Volumen de tu voz mientras escucha (0 a 1): la esfera crece con él. */
    val level: StateFlow<Float> = _level.asStateFlow()

    fun setLevel(value: Float) {
        _level.value = value.coerceIn(0f, 1f)
    }

    fun begin(agentName: String, locked: Boolean) {
        _state.value = Ui(Phase.LISTENING, agentName, "Escuchando…", locked)
    }

    fun update(phase: Phase, status: String) {
        _state.value = _state.value.copy(phase = phase, status = status)
    }

    /** Se desbloqueó en medio del pedido: la pantalla sobre el bloqueo ya no hace falta. */
    fun unlocked() {
        _state.value = _state.value.copy(locked = false)
    }

    fun idle() {
        _level.value = 0f
        _state.value = _state.value.copy(phase = Phase.IDLE, status = "")
    }
}
