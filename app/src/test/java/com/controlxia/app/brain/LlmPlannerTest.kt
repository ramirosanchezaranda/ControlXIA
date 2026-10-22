package com.controlxia.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** El modelo solo traduce a frases; la app las entiende y las hace. */
class LlmPlannerTest {

    @Test
    fun parsesSteps() {
        val raw = """```json
            {"steps": ["abrí mercado libre", "buscá zapatillas", "ordená de menor a mayor precio",
              "sacá captura de los primeros 4", "guardalo en una nota"]}
            ```"""
        val result = LlmPlanner.parse(raw) as LlmPlanner.Result.Steps
        assertEquals(5, result.phrases.size)
        // Las frases del modelo las entiende la app sola.
        val plan = TaskPlanner.plan(LlmPlanner.join(result.phrases), "Beto", LocalDate.of(2026, 10, 22))
        assertTrue(plan.unknown.toString(), plan.complete)
        assertEquals(TaskPlanner.Step.Capture(4), plan.steps[3])
    }

    @Test
    fun parsesAnswersAndRefusals() {
        assertEquals(LlmPlanner.Result.Answer("Canberra."), LlmPlanner.parse("""{"answer":"Canberra."}"""))
        assertEquals(LlmPlanner.Result.Cannot("comparar precios entre apps"), LlmPlanner.parse("""{"cannot":"comparar precios entre apps"}"""))
        // Sin JSON: lo que dijo es la respuesta.
        assertEquals(LlmPlanner.Result.Answer("Son las cinco."), LlmPlanner.parse("Son las cinco."))
    }

    @Test
    fun whatsappPhrasesFromTheModel() {
        val plan = TaskPlanner.plan(
            LlmPlanner.join(listOf("abrí el chat de Juan", "escribí Hola, ¿cómo estás?", "mandalo")),
            "Beto",
            LocalDate.of(2026, 10, 22),
        )
        assertEquals(
            listOf(TaskPlanner.Step.OpenChat("Juan"), TaskPlanner.Step.Write("Hola, ¿cómo estás?"), TaskPlanner.Step.SendWritten),
            plan.steps,
        )
    }
}
