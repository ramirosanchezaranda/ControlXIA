package com.controlxia.app.iot

import com.controlxia.app.iot.HomeCommands.Action
import com.controlxia.app.iot.HomeCommands.Command
import com.controlxia.app.iot.HomeDevice.Kind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Los pedidos para teles y compus, y el resto de la red de casa. */
class HomeCommandsTest {

    private fun one(text: String, tvs: List<String> = emptyList(), pcs: List<String> = emptyList()) =
        HomeCommands.parse(text, tvs, pcs)?.single()

    @Test
    fun tv() {
        assertEquals(Command(Kind.TV, Action.ON), one("Prendé la tele"))
        assertEquals(Command(Kind.TV, Action.OFF, "living"), one("apagá la tele del living"))
        assertEquals(Command(Kind.TV, Action.VOLUME_UP, times = 5), one("subile 5 a la tele"))
        assertEquals(Command(Kind.TV, Action.VOLUME_DOWN, times = 3), one("bajá el volumen de la tele"))
        assertEquals(Command(Kind.TV, Action.MUTE), one("silenciá la tele"))
        assertEquals(Command(Kind.TV, Action.PAUSE), one("poné pausa en la tele"))
        assertEquals(Command(Kind.TV, Action.OPEN, arg = "netflix"), one("poné Netflix en la tele"))
        assertEquals(Command(Kind.TV, Action.OFF), one("poné a dormir la tele"))
    }

    @Test
    fun pc() {
        assertEquals(Command(Kind.PC, Action.ON, "escritorio"), one("prendé la compu de escritorio"))
        assertEquals(Command(Kind.PC, Action.OFF), one("apagá la computadora"))
        assertEquals(Command(Kind.PC, Action.SLEEP), one("suspendé la notebook"))
        assertEquals(Command(Kind.PC, Action.LOCK), one("bloqueá la pc"))
        assertEquals(Command(Kind.PC, Action.RESTART), one("reiniciá la compu"))
        assertEquals(Command(Kind.PC, Action.OPEN, arg = "youtube"), one("abrí youtube en la compu"))
    }

    @Test
    fun byName() {
        assertEquals(Command(Kind.TV, Action.OFF, "Living"), one("apagá la del living", tvs = listOf("Living")))
        assertEquals(Command(Kind.PC, Action.LOCK, "gamer"), one("bloqueá la gamer", pcs = listOf("gamer")))
    }

    @Test
    fun severalSteps() {
        assertEquals(
            listOf(Command(Kind.TV, Action.ON), Command(Kind.TV, Action.OPEN, arg = "netflix")),
            HomeCommands.parse("prendé la tele y poné netflix"),
        )
    }

    @Test
    fun notForTheHouse() {
        assertNull(HomeCommands.parse("subí el volumen"))
        assertNull(HomeCommands.parse("poné una alarma a las 7"))
        assertNull(HomeCommands.parse("la tele"))
        assertNull(HomeCommands.parse("apagá la tele en 30 minutos"))
        assertNull(HomeCommands.parse("bloqueá la tele"))
    }

    @Test
    fun wakeOnLanPacket() {
        val mac = WakeOnLan.parseMac("aa-bb-cc-dd-ee-ff")!!
        val packet = WakeOnLan.packet(mac)
        assertEquals(102, packet.size)
        assertArrayEquals(ByteArray(6) { 0xFF.toByte() }, packet.copyOfRange(0, 6))
        assertArrayEquals(mac, packet.copyOfRange(96, 102))
        assertNull(WakeOnLan.parseMac("no es una mac"))
    }

    @Test
    fun broadcastAddress() {
        val ip = byteArrayOf(192.toByte(), 168.toByte(), 1, 37)
        assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 255.toByte()), LanInfo.broadcastOf(ip, 24))
        assertArrayEquals(byteArrayOf(10, 0, 255.toByte(), 255.toByte()), LanInfo.broadcastOf(byteArrayOf(10, 0, 3, 4), 16))
    }

    @Test
    fun pcRequestsAreSigned() {
        // Mismo resultado que el programita de Windows: HMAC-SHA256("ABCDE12345", "lock||1700000000|n1").
        val sig = PcProtocol.sign("abcde-12345", "lock", "", 1_700_000_000, "n1")
        assertEquals(64, sig.length)
        assertEquals(sig, PcProtocol.sign("ABCDE12345", "lock", "", 1_700_000_000, "n1"))
        val announce = PcProtocol.parseAnnounce("""{"app":"controlxia-pc","v":1,"name":"GAMER","port":48300,"macs":"AA:BB:CC:DD:EE:FF"}""")
        assertEquals("GAMER", announce?.name)
        assertEquals(listOf("AA:BB:CC:DD:EE:FF"), announce?.macs)
        assertNull(PcProtocol.parseAnnounce("""{"app":"otra"}"""))
    }

    @Test
    fun links() {
        assertEquals("https://www.netflix.com/title" to "Netflix", HomeLinks.tvApp("Netflix"))
        assertEquals("Disney+", HomeLinks.tvApp("disney plus")?.second)
        assertNull(HomeLinks.tvApp("canal de cocina"))
        assertEquals("https://www.youtube.com", HomeLinks.site("YouTube"))
        assertEquals("https://lanacion.com.ar", HomeLinks.site("lanacion punto com punto ar"))
        assertEquals("https://www.google.com/search?q=recetas+de+pizza", HomeLinks.site("recetas de pizza"))
    }
}
