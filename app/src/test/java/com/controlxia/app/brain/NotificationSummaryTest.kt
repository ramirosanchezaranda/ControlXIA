package com.controlxia.app.brain

import com.controlxia.app.brain.NotificationSummary.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cómo se leen en voz alta las notificaciones y cuál se abre. */
class NotificationSummaryTest {

    private val list = listOf(
        Entry("WhatsApp", "Juan", "hola, ¿venís?"),
        Entry("WhatsApp", "Fútbol 2, Pedro", "hoy a las 9"),
        Entry("Gmail", "Banco Galicia", "Tu resumen está disponible"),
        Entry("Gmail", "Banco Galicia", "Tu resumen está disponible"),
        Entry("Mercado Libre", "Mercado Libre", "Tu paquete llega hoy"),
        Entry("Clima", "", ""),
    )

    @Test
    fun readsAllGroupedByApp() = assertEquals(
        "Tenés 4 notificaciones. WhatsApp: Juan, hola, ¿venís?; Fútbol 2, Pedro, hoy a las 9. " +
            "Gmail: Banco Galicia, Tu resumen está disponible. Mercado Libre: Tu paquete llega hoy.",
        NotificationSummary.speak(list),
    )

    @Test
    fun filtersByAppOrSender() {
        assertEquals(
            "Tenés 2 notificaciones. WhatsApp: Juan, hola, ¿venís?; Fútbol 2, Pedro, hoy a las 9.",
            NotificationSummary.speak(list, "whatsapp"),
        )
        assertEquals(
            "Tenés una notificación. Gmail: Banco Galicia, Tu resumen está disponible.",
            NotificationSummary.speak(list, "banco"),
        )
        assertEquals("No tenés notificaciones de telegram.", NotificationSummary.speak(list, "telegram"))
        assertEquals("No tenés notificaciones.", NotificationSummary.speak(emptyList()))
    }

    @Test
    fun keepsItShort() {
        assertTrue(NotificationSummary.speak(List(11) { Entry("App$it", "T$it", "x") }).endsWith("Y 3 más."))
        assertTrue(NotificationSummary.speak(listOf(Entry("X", "Y", "a ".repeat(100)))).length < 160)
    }

    @Test
    fun picksWhichOneToOpen() {
        val items = list.map { it to it.app }
        assertEquals("Gmail", NotificationSummary.pick(items, "gmail")?.second)
        assertEquals("WhatsApp", NotificationSummary.pick(items, null)?.second)
        assertEquals("Fútbol 2, Pedro", NotificationSummary.pick(items, "pedro")?.first?.title)
    }
}
