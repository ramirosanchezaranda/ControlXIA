package com.controlxia.app.iot

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Lo justo de protobuf para hablar con las teles Android: escribir y leer
 * campos (números, textos, bytes y mensajes adentro de mensajes) y mandar cada
 * mensaje con su largo adelante, como lo hace la tele. Sin librerías: se prueba
 * en la PC.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun int(field: Int, value: Int): ProtoWriter = long(field, value.toLong())

    fun long(field: Int, value: Long): ProtoWriter {
        tag(field, WIRE_VARINT)
        varint(out, value)
        return this
    }

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, WIRE_BYTES)
        varint(out, value.size.toLong())
        out.write(value)
        return this
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, build: ProtoWriter.() -> Unit): ProtoWriter = bytes(field, ProtoWriter().apply(build).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wire: Int) = varint(out, ((field shl 3) or wire).toLong())

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_BYTES = 2

        fun varint(out: OutputStream, value: Long) {
            var v = value
            while (true) {
                if (v and 0x7FL.inv() == 0L) {
                    out.write(v.toInt())
                    return
                }
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }
    }
}

/** Un mensaje leído: número de campo → sus valores (números o bytes). */
class ProtoMessage private constructor(private val fields: Map<Int, List<Any>>) {

    fun has(field: Int): Boolean = fields.containsKey(field)

    fun long(field: Int): Long? = fields[field]?.firstOrNull() as? Long

    fun int(field: Int): Int? = long(field)?.toInt()

    fun bytes(field: Int): ByteArray? = fields[field]?.firstOrNull() as? ByteArray

    fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)

    fun message(field: Int): ProtoMessage? = bytes(field)?.let { parse(it) }

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val fields = mutableMapOf<Int, MutableList<Any>>()
            var pos = 0
            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (pos >= data.size) throw IOException("mensaje cortado")
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift >= 64) throw IOException("número inválido")
                }
            }
            while (pos < data.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> fields.getOrPut(field) { mutableListOf() }.add(varint())
                    1 -> pos += 8
                    2 -> {
                        val length = varint().toInt()
                        if (length < 0 || pos + length > data.size) throw IOException("largo inválido")
                        fields.getOrPut(field) { mutableListOf() }.add(data.copyOfRange(pos, pos + length))
                        pos += length
                    }
                    5 -> pos += 4
                    else -> throw IOException("tipo de campo desconocido")
                }
            }
            return ProtoMessage(fields)
        }
    }
}

/** Mensajes con su largo adelante (un varint), como los manda la tele. */
object ProtoFraming {

    fun write(out: OutputStream, message: ByteArray) {
        val framed = ByteArrayOutputStream()
        ProtoWriter.varint(framed, message.size.toLong())
        framed.write(message)
        // De una sola vez: algunas teles esperan el mensaje entero en un mismo paquete.
        out.write(framed.toByteArray())
        out.flush()
    }

    fun read(input: InputStream, max: Int = 1 shl 20): ByteArray {
        var length = 0L
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("la tele cerró la conexión")
            length = length or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 35) throw IOException("largo inválido")
        }
        if (length < 0 || length > max) throw IOException("mensaje demasiado largo")
        val data = ByteArray(length.toInt())
        var offset = 0
        while (offset < data.size) {
            val n = input.read(data, offset, data.size - offset)
            if (n < 0) throw EOFException("la tele cerró la conexión")
            offset += n
        }
        return data
    }
}
