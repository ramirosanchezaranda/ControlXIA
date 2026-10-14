package com.controlxia.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Cuándo se pausa la escucha para cuidar la batería. */
class ListeningPolicyTest {

    private fun reason(
        screenOn: Boolean = false,
        charging: Boolean = false,
        lowBattery: Boolean = false,
        powerSave: Boolean = false,
        listenScreenOff: Boolean = true,
        pauseOnLowBattery: Boolean = true,
    ) = ListeningPolicy.pauseReason(screenOn, charging, lowBattery, powerSave, listenScreenOff, pauseOnLowBattery)

    @Test
    fun alwaysListensWithScreenOnOrCharging() {
        assertNull(reason(screenOn = true, lowBattery = true, powerSave = true, listenScreenOff = false))
        assertNull(reason(charging = true, lowBattery = true, powerSave = true, listenScreenOff = false))
    }

    @Test
    fun screenOffListensByDefault() = assertNull(reason())

    @Test
    fun pausesWhenAskedOrBatteryIsLow() {
        assertEquals("En pausa con la pantalla apagada", reason(listenScreenOff = false))
        assertEquals("En pausa por batería baja", reason(lowBattery = true))
        assertEquals("En pausa por el ahorro de batería", reason(powerSave = true))
    }

    @Test
    fun lowBatteryPauseCanBeTurnedOff() = assertNull(reason(lowBattery = true, powerSave = true, pauseOnLowBattery = false))
}
