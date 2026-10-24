package com.controlxia.app.iot

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

/** El protocolo del control remoto de las teles Android, sin tele. */
class AtvProtocolTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun pairingRequestBytes() {
        // protocol_version=2, status=200, pairing_request{service_name="atvremote", client_name="X"}
        val expected = "0802" + "10c801" + "520e" + "0a0961747672656d6f7465" + "120158"
        assertEquals(expected, hex(Polo.pairingRequest("X")))
    }

    @Test
    fun optionsAndConfigurationBytes() {
        // options{input_encodings{type=HEX, symbol_length=6}, preferred_role=INPUT}
        assertEquals("0802" + "10c801" + "a20108" + "0a0408031006" + "1801", hex(Polo.options()))
        // configuration{encoding{type=HEX, symbol_length=6}, client_role=INPUT}
        assertEquals("0802" + "10c801" + "f20108" + "0a0408031006" + "1001", hex(Polo.configuration()))
    }

    @Test
    fun parsesTheTvReplies() {
        val ack = ProtoWriter().int(1, 2).int(2, 200).message(11) { string(1, "Living") }.toByteArray()
        assertEquals(Polo.Kind.PAIRING_ACK, Polo.parse(ack).kind)
        val configurationAck = ProtoWriter().int(1, 2).int(2, 200).message(31) {}.toByteArray()
        assertEquals(Polo.Kind.CONFIGURATION_ACK, Polo.parse(configurationAck).kind)
        val badSecret = ProtoWriter().int(1, 2).int(2, 402).toByteArray()
        assertEquals(Polo.Kind.ERROR, Polo.parse(badSecret).kind)
        assertEquals(Polo.STATUS_BAD_SECRET, Polo.parse(badSecret).status)
    }

    @Test
    fun framingRoundTrip() {
        val out = ByteArrayOutputStream()
        val long = ByteArray(300) { it.toByte() }
        ProtoFraming.write(out, Polo.options())
        ProtoFraming.write(out, long)
        val input = ByteArrayInputStream(out.toByteArray())
        assertArrayEquals(Polo.options(), ProtoFraming.read(input))
        assertArrayEquals(long, ProtoFraming.read(input))
    }

    @Test
    fun remoteMessages() {
        assertEquals("520408" + "1a1003", hex(RemoteMessages.key(RemoteMessages.KEY_POWER)))
        assertEquals("d2050b" + "0a0973706f746966793a2f", hex(RemoteMessages.appLink("spotify:/")))
        val ping = ProtoWriter().message(8) { int(1, 7); int(2, 1) }.toByteArray()
        assertEquals(RemoteMessages.Incoming.Ping(7), RemoteMessages.parse(ping))
        // proto3: "apagada" llega como mensaje vacío.
        val off = ProtoWriter().message(40) {}.toByteArray()
        assertEquals(RemoteMessages.Incoming.Started(false), RemoteMessages.parse(off))
        val on = ProtoWriter().message(40) { int(1, 1) }.toByteArray()
        assertEquals(RemoteMessages.Incoming.Started(true), RemoteMessages.parse(on))
        val configure = ProtoWriter().message(1) { int(1, 622) }.toByteArray()
        assertEquals(RemoteMessages.Incoming.Configure(622), RemoteMessages.parse(configure))
    }

    @Test
    fun secretMatchesTheReferenceFormula() {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val client = generator.generateKeyPair().public as RSAPublicKey
        val server = generator.generateKeyPair().public as RSAPublicKey
        // Como androidtvremote2: bytes.fromhex(f"{n:X}") y bytes.fromhex(f"0{e:X}").
        fun fromHex(text: String) = BigInteger(text, 16).toByteArray().let { if (it[0] == 0.toByte() && it.size > 1) it.copyOfRange(1, it.size) else it }
        val nonce = "4A2F"
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(fromHex(client.modulus.toString(16)))
            update(fromHex("0" + client.publicExponent.toString(16)))
            update(fromHex(server.modulus.toString(16)))
            update(fromHex("0" + server.publicExponent.toString(16)))
            update(byteArrayOf(0x4A, 0x2F))
        }.digest()
        val code = "%02X".format(digest[0]) + nonce
        val alpha = AtvSecret.alpha(client, server, code.lowercase())
        assertNotNull(alpha)
        assertArrayEquals(digest, alpha)
        // Un carácter mal copiado: el primer byte no coincide.
        val wrong = "%02X".format((digest[0] + 1) and 0xFF) + nonce
        assertNull(AtvSecret.alpha(client, server, wrong))
        assertTrue(AtvSecret.isValid("a1 b2 c3"))
    }

    @Test
    fun selfSignedCertificateIsValid() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = SelfSignedCert.create(keys, "ControlXIA")
        cert.checkValidity()
        cert.verify(keys.public)
        assertEquals(3, cert.version)
        assertTrue(cert.subjectX500Principal.name.contains("CN=ControlXIA"))
        assertEquals(keys.public, cert.publicKey)
    }
}
