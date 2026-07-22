package com.controlxia.app.brain

/**
 * Memoria conversacional corta: guarda los últimos turnos en memoria (mientras
 * el servicio vive) para dar contexto a pedidos encadenados ("y ahora mandale
 * otro"). No se persiste: se reinicia con el servicio.
 */
class ConversationMemory(private val maxTurns: Int = 10) {

    private val turns = ArrayDeque<ChatMessage>()

    fun add(role: ChatRole, text: String) {
        turns.addLast(ChatMessage(role, text))
        while (turns.size > maxTurns) turns.removeFirst()
    }

    fun history(): List<ChatMessage> = turns.toList()

    fun clear() = turns.clear()
}
