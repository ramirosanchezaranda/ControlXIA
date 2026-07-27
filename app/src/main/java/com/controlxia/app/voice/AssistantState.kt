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

    fun begin(agentName: String, locked: Boolean) {
        _state.value = Ui(Phase.LISTENING, agentName, "Escuchando…", locked)
    }

    fun update(phase: Phase, status: String) {
        _state.value = _state.value.copy(phase = phase, status = status)
    }

    fun idle() {
        _state.value = _state.value.copy(phase = Phase.IDLE, status = "")
    }
}
