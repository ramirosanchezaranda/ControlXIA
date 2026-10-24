package com.controlxia.app.iot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cómo se habla con el programita de ControlXIA en Windows (pc/ControlXIA-PC.ps1):
 *
 * - Para encontrarlo: "CONTROLXIA?" por UDP a toda la red (puerto 48301); contesta
 *   con su nombre, su puerto y sus MAC (para prenderla con Wake-on-LAN).
 * - Para pedirle algo: una línea JSON por TCP (puerto 48300) firmada con el código
 *   que muestra la PC al instalarlo (HMAC-SHA256 del pedido, la hora y un número
 *   al azar), así nadie más en la red puede apagarla. Contesta otra línea JSON.
 *
 * Sin Android: se prueba en la PC.
 */
object PcProtocol {
    const val TCP_PORT = 48300
    const val UDP_PORT = 48301
    const val DISCOVER = "CONTROLXIA?"

    private val json = Json { ignoreUnknownKeys = true }

    /** El código como lo usa la PC: mayúsculas, sin guiones ni espacios. */
    fun cleanCode(code: String): String = code.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    fun sign(code: String, cmd: String, arg: String, ts: Long, nonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(cleanCode(code).toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("$cmd|$arg|$ts|$nonce".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun request(code: String, cmd: String, arg: String, ts: Long, nonce: String): String = buildJsonObject {
        put("cmd", cmd)
        put("arg", arg)
        put("ts", ts)
        put("nonce", nonce)
        put("sig", sign(code, cmd, arg, ts, nonce))
    }.toString()

    /** La respuesta a un pedido. */
    class Answer(val ok: Boolean, val message: String, val name: String?, val macs: List<String>)

    fun parseAnswer(line: String): Answer? = runCatching {
        val obj = json.parseToJsonElement(line).jsonObject
        Answer(
            ok = obj["ok"]?.jsonPrimitive?.content == "true",
            message = obj["msg"]?.jsonPrimitive?.content.orEmpty(),
            name = obj["name"]?.jsonPrimitive?.content,
            macs = macs(obj),
        )
    }.getOrNull()

    /** Lo que contesta una PC al buscarla. */
    class Announce(val name: String, val port: Int, val macs: List<String>)

    fun parseAnnounce(text: String): Announce? = runCatching {
        val obj = json.parseToJsonElement(text).jsonObject
        if (obj["app"]?.jsonPrimitive?.content != "controlxia-pc") return null
        Announce(
            name = obj["name"]?.jsonPrimitive?.content ?: "PC",
            port = obj["port"]?.jsonPrimitive?.content?.toIntOrNull() ?: TCP_PORT,
            macs = macs(obj),
        )
    }.getOrNull()

    /** PowerShell a veces manda una sola MAC como texto en vez de lista. */
    private fun macs(obj: JsonObject): List<String> {
        val value = obj["macs"] ?: return emptyList()
        return runCatching { value.jsonArray.map { it.jsonPrimitive.content } }
            .recoverCatching { listOf(value.jsonPrimitive.content) }
            .getOrDefault(emptyList())
            .filter { WakeOnLan.parseMac(it) != null }
    }
}
