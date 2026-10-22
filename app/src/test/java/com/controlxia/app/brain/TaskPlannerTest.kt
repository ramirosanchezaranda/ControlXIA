package com.controlxia.app.brain

import com.controlxia.app.brain.TaskPlanner.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Pedidos de varios pasos que la app resuelve sola, sin el modelo. */
class TaskPlannerTest {

    private val today = LocalDate.of(2026, 10, 22)

    private fun plan(text: String) = TaskPlanner.plan(text, "Beto", today)

    @Test
    fun mercadoLibreCheapestToNotes() {
        val plan = plan(
            "Beto, necesito que entres en Mercado Libre, busques zapatillas, filtres por productos de menor a mayor " +
                "y saques captura de los primeros 4 productos más baratos y lo guardes en un archivo de notas",
        )
        assertEquals(emptyList<String>(), plan.unknown)
        assertEquals(
            listOf(
                Step.Direct(DirectCommands.OpenApp("mercado libre")),
                Step.Search("mercadolibre", "zapatillas", null),
                Step.Sort("price_low"),
                Step.Capture(4),
                Step.SaveNote(null),
            ),
            plan.steps,
        )
    }

    @Test
    fun imperativeForms() {
        val plan = plan("abrí mercado libre, buscá zapatillas nike más baratas, sacá captura de los primeros cuatro y guardalo en una nota")
        assertTrue(plan.unknown.toString(), plan.complete)
        assertEquals(Step.Search("mercadolibre", "zapatillas nike", "price_low"), plan.steps[1])
        assertEquals(Step.Capture(4), plan.steps[2])
        assertEquals(Step.SaveNote(null), plan.steps[3])
    }

    @Test
    fun whatsappChatWriteAndSend() {
        val plan = plan("abrí WhatsApp, entrá en el chat de Juan y escribile que mañana llego tarde y mandalo")
        assertTrue(plan.unknown.toString(), plan.complete)
        assertEquals(
            listOf(
                Step.Direct(DirectCommands.OpenApp("whatsapp")),
                Step.OpenChat("Juan"),
                Step.Write("Mañana llego tarde"),
                Step.SendWritten,
            ),
            plan.steps,
        )
    }

    @Test
    fun whatsappChatAudio() {
        val plan = plan("entrá al chat de Rebeca y mandale un audio que diga feliz cumpleaños")
        assertEquals(listOf(Step.OpenChat("Rebeca"), Step.AudioHere("Feliz cumpleaños")), plan.steps)
        val recorded = plan("necesito que abras el grupo fútbol 2 y le mandes un audio")
        assertEquals(listOf(Step.OpenChat("fútbol 2"), Step.AudioHere(null)), recorded.steps)
    }

    @Test
    fun messageWithAndStaysOneMessage() {
        val plan = plan("abrí el chat de Juan y decile que llego tarde y que traiga pan")
        assertEquals(listOf(Step.OpenChat("Juan"), Step.SendHere("Llego tarde y que traiga pan")), plan.steps)
    }

    @Test
    fun searchQueryWithAndStaysOneSearch() {
        val plan = plan("entrá a mercado libre y buscá zapatillas y medias")
        assertEquals(Step.Search("mercadolibre", "zapatillas y medias", null), plan.steps.last())
        assertTrue(plan.complete)
    }

    @Test
    fun mixesWithDirectCommandsAndSkills() {
        val plan = plan("poné una alarma a las 7, subí el volumen y después abrí Spotify")
        assertTrue(plan.unknown.toString(), plan.complete)
        assertEquals(3, plan.steps.size)
        assertEquals(Step.Skill(LocalSkills.Tool("set_alarm", mapOf("hour" to 7, "minute" to 0))), plan.steps[0])
        assertEquals(Step.Direct(DirectCommands.OpenApp("spotify")), plan.steps[2])
    }

    @Test
    fun unknownPartsAreReported() {
        val plan = plan("abrí mercado libre y comparalo con amazon")
        assertFalse(plan.complete)
        assertEquals(listOf("comparalo con amazon"), plan.unknown)
    }

    @Test
    fun questionsGoToTheModel() {
        assertTrue(TaskPlanner.isQuestion("¿cuál es la capital de Australia?"))
        assertTrue(TaskPlanner.isQuestion("Beto, qué es un agujero negro", "Beto"))
        assertTrue(TaskPlanner.isQuestion("explicame cómo funciona la fotosíntesis"))
        assertFalse(TaskPlanner.isQuestion("abrí mercado libre y buscá zapatillas"))
    }

    @Test
    fun verbsBecomeOrders() {
        assertEquals("busca zapatillas", TaskPlanner.imperative("busques zapatillas"))
        assertEquals("guardalo en una nota", TaskPlanner.imperative("lo guardes en una nota"))
        assertEquals("mandale un audio", TaskPlanner.imperative("le mandes un audio"))
        assertEquals("guardalo", TaskPlanner.imperative("guardarlo"))
        assertEquals("entra en Mercado Libre", TaskPlanner.imperative("entres en Mercado Libre"))
    }
}
