package com.controlxia.app.brain

import com.controlxia.app.brain.MessageEdit.Result.Edited
import com.controlxia.app.brain.MessageEdit.Result.NotFound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Corregir de palabra el mensaje que espera el "sí". */
class MessageEditTest {

    private val text = "todos estos mensajes los escribió la guía"

    @Test
    fun replaceOneWord() {
        val fixed = Edited("todos estos mensajes los escribió la IA")
        assertEquals(fixed, MessageEdit.apply("cambiá guía por IA", text))
        assertEquals(fixed, MessageEdit.apply("No, cambia la guía por la IA.", text))
        assertEquals(fixed, MessageEdit.apply("reemplazá guia por IA", text))
        assertEquals(fixed, MessageEdit.apply("en vez de guía poné IA", text))
        assertEquals(fixed, MessageEdit.apply("poné IA en vez de guía", text))
        assertEquals(fixed, MessageEdit.apply("no es guía, es IA", text))
        assertEquals(fixed, MessageEdit.apply("no dije guía, dije IA", text))
    }

    @Test
    fun replaceAWordThatIsNotThere() {
        assertEquals(NotFound("perro"), MessageEdit.apply("cambiá perro por gato", text))
    }

    @Test
    fun replaceTheWholeMessage() {
        assertEquals(Edited("llego en diez"), MessageEdit.apply("no, ponele llego en diez", text))
        assertEquals(Edited("llego en diez"), MessageEdit.apply("no, que diga «llego en diez»", text))
        assertEquals(Edited("llego en diez"), MessageEdit.apply("mejor decile que llego en diez", text))
    }

    @Test
    fun anotherPersonIsNotACorrection() {
        assertNull(MessageEdit.apply("no, decile a Juan que llego tarde", text))
    }

    @Test
    fun appendAndRemove() {
        assertEquals(Edited("llego tarde un abrazo"), MessageEdit.apply("agregale un abrazo", "llego tarde"))
        assertEquals(Edited("llego tarde"), MessageEdit.apply("sacale muy", "llego muy tarde"))
        assertEquals(NotFound("hoy"), MessageEdit.apply("borrá hoy", "llego tarde"))
    }

    @Test
    fun answersAreNotEdits() {
        assertNull(MessageEdit.apply("sí", text))
        assertNull(MessageEdit.apply("no", text))
        assertNull(MessageEdit.apply("dale mandalo", text))
        assertNull(MessageEdit.apply("poné una alarma a las 7", text))
    }
}
