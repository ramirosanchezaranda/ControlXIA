package com.controlxia.app.actions

import com.controlxia.app.actions.ScreenItems.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Productos y precios de una lista de resultados, leídos de la pantalla. */
class ScreenItemsTest {

    private val mercadoLibre = listOf(
        "Ordenar por", "Menor precio", "Filtrar (2)", "5.432 resultados",
        "Zapatillas Topper Rail Hombre Negro", "Antes: 32999 pesos", "$", "19.999", "40% OFF",
        "Mismo precio en 6 cuotas de $ 3.333", "Envío gratis",
        "Patrocinado",
        "Zapatillas Deportivas Running Mujer Livianas", "$ 21.500", "Llega gratis mañana",
        "Zapatilla Urbana Unisex Lona Clásica", "22999 pesos", "4,5 estrellas, 120 opiniones",
        "Zapatillas Fila Disruptor Blanco Original", "$ 24.990,50",
    )

    @Test
    fun readsTitlesAndCurrentPrices() {
        assertEquals(
            listOf(
                Item("Zapatillas Topper Rail Hombre Negro", "$ 19.999"),
                Item("Zapatillas Deportivas Running Mujer Livianas", "$ 21.500"),
                Item("Zapatilla Urbana Unisex Lona Clásica", "$ 22999"),
                Item("Zapatillas Fila Disruptor Blanco Original", "$ 24.990,50"),
            ),
            ScreenItems.extract(mercadoLibre, 4),
        )
    }

    @Test
    fun stopsAtTheRequestedCount() {
        assertEquals(2, ScreenItems.extract(mercadoLibre, 2).size)
    }

    @Test
    fun installmentsAndOldPricesAreNotPrices() {
        assertNull(ScreenItems.price("Mismo precio en 6 cuotas de $ 3.333"))
        assertNull(ScreenItems.price("Antes: 32999 pesos"))
        assertEquals("$ 21.500", ScreenItems.price("$ 21.500"))
        assertEquals("US$ 120", ScreenItems.price("US$ 120"))
    }
}
