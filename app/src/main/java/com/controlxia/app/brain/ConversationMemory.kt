package com.controlxia.app.brain

/**
 * Memoria conversacional corta: guarda los últimos intercambios en memoria
 * (mientras el servicio vive) para dar contexto a pedidos encadenados ("y
 * ahora mandale otro"). No se persiste: se reinicia con el servicio.
 *
 * Solo guarda intercambios completos (pedido + respuesta), así el historial
 * siempre empieza con un turno del usuario y alterna roles, que es lo que
 * exigen las APIs. Un pedido que falló no deja un turno suelto. Tras un rato
 * sin hablar se olvida todo, para que un pedido nuevo no arrastre contexto viejo.
 */
class ConversationMemory(
    private val maxExchanges: Int = 6,
    private val ttlMs: Long = 10 * 60 * 1000L,
) {

    private val turns = ArrayDeque<ChatMessage>()
    private var lastAt = 0L

    @Synchronized
    fun addExchange(user: String, assistant: String) {
        expireIfStale()
        turns.addLast(ChatMessage(ChatRole.USER, user))
        turns.addLast(ChatMessage(ChatRole.ASSISTANT, assistant))
        while (turns.size > maxExchanges * 2) {
            turns.removeFirst()
            turns.removeFirst()
        }
        lastAt = System.currentTimeMillis()
    }

    @Synchronized
    fun history(): List<ChatMessage> {
        expireIfStale()
        return turns.toList()
    }

    @Synchronized
    fun clear() = turns.clear()

    private fun expireIfStale() {
        if (turns.isNotEmpty() && System.currentTimeMillis() - lastAt > ttlMs) turns.clear()
    }
}
