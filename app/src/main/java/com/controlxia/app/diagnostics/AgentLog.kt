package com.controlxia.app.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de lo que hizo el agente en los últimos pedidos (qué pidió el
 * modelo, qué devolvió cada herramienta, errores). Sirve para entender por qué
 * algo no funcionó en un teléfono real: se ve en el panel y se puede copiar.
 *
 * Vive solo en memoria y en el teléfono; incluye nombres y textos de los
 * mensajes, así que no se manda a ningún lado salvo que el usuario lo copie.
 */
object AgentLog {

    class Entry(val time: Long, val text: String)

    private const val MAX_ENTRIES = 150
    private const val MAX_TEXT = 400

    private val entries = ArrayDeque<Entry>()

    private val _changes = MutableStateFlow(0)

    /** Cambia cada vez que se agrega o borra algo (para que la pantalla se actualice sola). */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    val version: Int get() = _changes.value

    @Synchronized
    fun add(text: String) {
        entries.addLast(Entry(System.currentTimeMillis(), text.replace('\n', ' ').take(MAX_TEXT)))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        _changes.value++
    }

    @Synchronized
    fun recent(limit: Int): List<Entry> = entries.toList().takeLast(limit)

    @Synchronized
    fun clear() {
        entries.clear()
        _changes.value++
    }

    @Synchronized
    fun asText(): String {
        val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
        return entries.joinToString("\n") { "${clock.format(Date(it.time))} ${it.text}" }
    }
}
