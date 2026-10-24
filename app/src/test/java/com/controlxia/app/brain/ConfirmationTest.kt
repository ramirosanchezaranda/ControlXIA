package com.controlxia.app.brain

import com.controlxia.app.brain.Confirmation.Answer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Los "sí" y "no" que se pueden tomar sin esperar el silencio. */
class ConfirmationTest {

    @Test
    fun plainYesAndNo() {
        assertEquals(Answer.YES, Confirmation.plainAnswer("Sí"))
        assertEquals(Answer.YES, Confirmation.plainAnswer("dale, mandalo"))
        assertEquals(Answer.YES, Confirmation.plainAnswer("sí por favor"))
        assertEquals(Answer.NO, Confirmation.plainAnswer("no"))
        assertEquals(Answer.NO, Confirmation.plainAnswer("no, no"))
    }

    @Test
    fun anythingMoreIsNotPlain() {
        // Puede seguir: "no, ponele…", "bueno, mejor no", "sí, pero…".
        assertNull(Confirmation.plainAnswer("no ponele"))
        assertNull(Confirmation.plainAnswer("bueno"))
        assertNull(Confirmation.plainAnswer("sí pero"))
        assertNull(Confirmation.plainAnswer(""))
    }
}
