package com.controlxia.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Lo que la app muestra del servicio de escucha, como un estado que se puede
 * observar: las pantallas se actualizan cuando cambia algo, sin preguntar cada
 * segundo. Lo escribe solo [WakeWordService].
 */
object ServiceStatus {

    data class Snapshot(
        /** El usuario dejó la escucha encendida (aunque Android la haya frenado). */
        val enabled: Boolean = false,
        /** El servicio está corriendo. */
        val running: Boolean = false,
        /** El micrófono está esperando el nombre. */
        val wakeActive: Boolean = false,
        /** Qué pasa con la escucha, legible ("Escuchando «Beto»", el motivo de una pausa…). */
        val wakeStatus: String? = null,
        /** La escucha está parada por un error (no mientras se prepara). */
        val wakeFailed: Boolean = false,
        val lastCommand: String? = null,
        val lastReply: String? = null,
        /** Error técnico del último pedido al modelo (null si salió bien). */
        val lastError: String? = null,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    internal fun update(change: (Snapshot) -> Snapshot) = _state.update(change)
}
