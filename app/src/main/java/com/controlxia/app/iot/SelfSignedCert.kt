package com.controlxia.app.iot

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Un certificado X.509 firmado por su propia clave: con él ControlXIA se
 * presenta ante las teles (la tele lo recuerda al vincular). Armado a mano en
 * DER para no sumar librerías; se prueba en la PC.
 */
object SelfSignedCert {

    private const val SHA256_WITH_RSA = "1.2.840.113549.1.1.11"
    private const val COMMON_NAME = "2.5.4.3"

    fun create(keys: KeyPair, commonName: String, now: Date = Date(), years: Int = 20): X509Certificate {
        val notBefore = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            time = now
            add(Calendar.DAY_OF_YEAR, -1)
        }.time
        val notAfter = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            time = now
            add(Calendar.YEAR, years)
        }.time
        val algorithm = seq(oid(SHA256_WITH_RSA), byteArrayOf(0x05, 0x00))
        val name = seq(set(seq(oid(COMMON_NAME), der(0x0C, commonName.toByteArray(Charsets.UTF_8)))))
        val tbs = seq(
            der(0xA0, integer(BigInteger.valueOf(2))),
            integer(BigInteger(63, SecureRandom()).add(BigInteger.ONE)),
            algorithm,
            name,
            seq(time(notBefore), time(notAfter)),
            name,
            keys.public.encoded,
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private)
            update(tbs)
            sign()
        }
        val certificate = seq(tbs, algorithm, der(0x03, byteArrayOf(0) + signature))
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificate)) as X509Certificate
    }

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val length = content.size
        when {
            length < 0x80 -> out.write(length)
            length < 0x100 -> {
                out.write(0x81)
                out.write(length)
            }
            length < 0x10000 -> {
                out.write(0x82)
                out.write(length shr 8)
                out.write(length and 0xFF)
            }
            else -> {
                out.write(0x83)
                out.write(length shr 16)
                out.write((length shr 8) and 0xFF)
                out.write(length and 0xFF)
            }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun seq(vararg parts: ByteArray): ByteArray = der(0x30, parts.fold(ByteArray(0)) { acc, part -> acc + part })

    private fun set(vararg parts: ByteArray): ByteArray = der(0x31, parts.fold(ByteArray(0)) { acc, part -> acc + part })

    private fun integer(value: BigInteger): ByteArray = der(0x02, value.toByteArray())

    private fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        for (arc in arcs.drop(2)) {
            val chunks = mutableListOf<Int>()
            var v = arc
            do {
                chunks += (v and 0x7F).toInt()
                v = v shr 7
            } while (v > 0)
            chunks.reversed().forEachIndexed { i, chunk -> out.write(if (i < chunks.size - 1) chunk or 0x80 else chunk) }
        }
        return der(0x06, out.toByteArray())
    }

    /** UTCTime hasta 2049; después, GeneralizedTime (lo pide el estándar). */
    private fun time(date: Date): ByteArray {
        val year = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = date }.get(Calendar.YEAR)
        val (tag, pattern) = if (year < 2050) 0x17 to "yyMMddHHmmss'Z'" else 0x18 to "yyyyMMddHHmmss'Z'"
        val text = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)
        return der(tag, text.toByteArray(Charsets.US_ASCII))
    }
}
