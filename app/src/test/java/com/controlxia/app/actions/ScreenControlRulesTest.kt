package com.controlxia.app.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Qué botones piden el "sí" y qué campos son buscadores. */
class ScreenControlRulesTest {

    @Test
    fun riskyButtons() {
        listOf(
            "Enviar", "Comprar ahora", "Ir a pagar", "Confirmar compra", "Hacer pedido", "Pedir",
            "Reservar", "Llamar", "Eliminar chat", "send button", "Place order", "Transferir",
        ).forEach { assertTrue(it, ScreenControl.isRisky(it)) }
        listOf("Buscar", "Inicio", "Mis pedidos", "Facebook", "Volver", "Ver más").forEach {
            assertFalse(it, ScreenControl.isRisky(it))
        }
    }

    @Test
    fun searchFields() {
        assertTrue(ScreenControl.looksLikeSearch("Buscar en Mercado Libre"))
        assertTrue(ScreenControl.looksLikeSearch("search src text"))
        assertTrue(ScreenControl.looksLikeSearch("¿Qué estás buscando?"))
        assertFalse(ScreenControl.looksLikeSearch("Mensaje entry"))
    }

    @Test
    fun idsAreSplitIntoWords() {
        assertEquals("btn Send", ScreenControl.splitId("btnSend"))
        assertEquals("send button", ScreenControl.splitId("send_button"))
        assertTrue(ScreenControl.isRisky(ScreenControl.splitId("btnSend")))
    }
}
