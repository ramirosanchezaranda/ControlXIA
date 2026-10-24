package com.controlxia.app.iot

import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

/**
 * Los mensajes del control remoto de las teles Android (el mismo protocolo que
 * usa la app de control remoto de Google), según polo.proto y
 * remotemessage.proto del proyecto abierto androidtvremote2.
 *
 * - Vinculación (puerto 6467): pedido → opciones → configuración → la tele
 *   muestra un código de 6 caracteres → secreto.
 * - Control (puerto 6466): la tele se presenta, contestamos qué sabemos hacer,
 *   responde a los "ping" y acepta teclas y enlaces para abrir apps.
 */
object Polo {
    const val STATUS_OK = 200
    const val STATUS_BAD_SECRET = 402
    private const val VERSION = 2
    private const val HEXADECIMAL = 3
    private const val ROLE_INPUT = 1
    private const val CODE_LENGTH = 6

    enum class Kind { PAIRING_ACK, OPTIONS, CONFIGURATION_ACK, SECRET_ACK, ERROR, OTHER }

    class Reply(val kind: Kind, val status: Int)

    private fun outer(body: ProtoWriter.() -> Unit): ByteArray =
        ProtoWriter().int(1, VERSION).int(2, STATUS_OK).apply(body).toByteArray()

    fun pairingRequest(clientName: String): ByteArray = outer {
        message(10) {
            string(1, "atvremote")
            string(2, clientName)
        }
    }

    fun options(): ByteArray = outer {
        message(20) {
            message(1) {
                int(1, HEXADECIMAL)
                int(2, CODE_LENGTH)
            }
            int(3, ROLE_INPUT)
        }
    }

    fun configuration(): ByteArray = outer {
        message(30) {
            message(1) {
                int(1, HEXADECIMAL)
                int(2, CODE_LENGTH)
            }
            int(2, ROLE_INPUT)
        }
    }

    fun secret(alpha: ByteArray): ByteArray = outer { message(40) { bytes(1, alpha) } }

    fun parse(data: ByteArray): Reply {
        val message = ProtoMessage.parse(data)
        val status = message.int(2) ?: 0
        val kind = when {
            status != STATUS_OK -> Kind.ERROR
            message.has(11) -> Kind.PAIRING_ACK
            message.has(20) -> Kind.OPTIONS
            message.has(31) -> Kind.CONFIGURATION_ACK
            message.has(41) -> Kind.SECRET_ACK
            else -> Kind.OTHER
        }
        return Reply(kind, status)
    }
}

/** El "secreto" de la vinculación: prueba que viste el código que muestra la tele. */
object AtvSecret {

    private val CODE = Regex("[0-9A-F]{6}")

    /** Deja el código como lo espera la tele ("a1 b2 c3" → "A1B2C3"). */
    fun clean(code: String): String = code.uppercase().filter { it.isLetterOrDigit() }

    fun isValid(code: String): Boolean = CODE.matches(clean(code))

    /**
     * SHA-256 de las dos claves públicas y los últimos 4 caracteres del código;
     * los 2 primeros tienen que coincidir con el primer byte del resultado. null
     * si no coinciden (el código se escribió mal).
     */
    fun alpha(client: RSAPublicKey, server: RSAPublicKey, code: String): ByteArray? {
        val pin = clean(code)
        if (!CODE.matches(pin)) return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(unsigned(client.modulus))
        digest.update(unsigned(client.publicExponent))
        digest.update(unsigned(server.modulus))
        digest.update(unsigned(server.publicExponent))
        digest.update(hex(pin.substring(2)))
        val hash = digest.digest()
        return hash.takeIf { (it[0].toInt() and 0xFF) == pin.substring(0, 2).toInt(16) }
    }

    /** Los bytes del número sin el cero de signo de adelante. */
    fun unsigned(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** Los mensajes de la conexión de control (puerto 6466). */
object RemoteMessages {

    /** Lo que pedimos poder hacer: ping, teclas, encendido, volumen y abrir apps. */
    const val FEATURES = 1 or 2 or 32 or 64 or 512
    const val SHORT = 3

    // Teclas de Android (KeyEvent).
    const val KEY_HOME = 3
    const val KEY_BACK = 4
    const val KEY_VOLUME_UP = 24
    const val KEY_VOLUME_DOWN = 25
    const val KEY_POWER = 26
    const val KEY_PLAY_PAUSE = 85
    const val KEY_NEXT = 87
    const val KEY_PREVIOUS = 88
    const val KEY_PLAY = 126
    const val KEY_PAUSE = 127
    const val KEY_MUTE = 164

    sealed interface Incoming {
        data class Configure(val features: Int) : Incoming
        data object SetActive : Incoming
        data class Ping(val value: Int) : Incoming
        data class Started(val on: Boolean) : Incoming
        data class Volume(val level: Int, val max: Int, val muted: Boolean) : Incoming
        data object Error : Incoming
        data object Other : Incoming
    }

    fun configure(features: Int): ByteArray = ProtoWriter().message(1) {
        int(1, features)
        message(2) {
            int(3, 1)
            string(4, "1")
            string(5, "atvremote")
            string(6, "1.0.0")
        }
    }.toByteArray()

    fun setActive(features: Int): ByteArray = ProtoWriter().message(2) { int(1, features) }.toByteArray()

    fun pingResponse(value: Int): ByteArray = ProtoWriter().message(9) { int(1, value) }.toByteArray()

    fun key(code: Int, direction: Int = SHORT): ByteArray = ProtoWriter().message(10) {
        int(1, code)
        int(2, direction)
    }.toByteArray()

    fun appLink(link: String): ByteArray = ProtoWriter().message(90) { string(1, link) }.toByteArray()

    fun parse(data: ByteArray): Incoming {
        val message = ProtoMessage.parse(data)
        return when {
            message.has(1) -> Incoming.Configure(message.message(1)?.int(1) ?: 0)
            message.has(2) -> Incoming.SetActive
            message.has(3) -> Incoming.Error
            message.has(8) -> Incoming.Ping(message.message(8)?.int(1) ?: 0)
            message.has(40) -> Incoming.Started(message.message(40)?.long(1) == 1L)
            message.has(50) -> message.message(50).let { volume ->
                Incoming.Volume(volume?.int(7) ?: 0, volume?.int(6) ?: 0, volume?.long(8) == 1L)
            }
            else -> Incoming.Other
        }
    }
}
