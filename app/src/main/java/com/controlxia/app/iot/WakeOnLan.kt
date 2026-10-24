package com.controlxia.app.iot

import android.content.Context
import android.net.ConnectivityManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Prender una PC por la red (Wake-on-LAN): el "paquete mágico" con su
 * dirección física (MAC) 16 veces, a todos en la red. La PC tiene que tener
 * Wake-on-LAN activado (BIOS y placa de red); casi siempre anda solo por cable.
 */
object WakeOnLan {

    private val MAC = Regex("^[0-9A-Fa-f]{2}([:-]?[0-9A-Fa-f]{2}){5}$")

    /** "AA:BB:CC:DD:EE:FF", "AA-BB-…" o "AABBCC…" → 6 bytes; null si no es una MAC. */
    fun parseMac(text: String): ByteArray? {
        val trimmed = text.trim()
        if (!MAC.matches(trimmed)) return null
        val hex = trimmed.filter { it.isLetterOrDigit() }
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun packet(mac: ByteArray): ByteArray {
        require(mac.size == 6)
        return ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { mac[it % 6] }
    }

    /** Manda el paquete a todas las direcciones de difusión de la red, a los puertos 9 y 7. true si salió alguno. */
    fun send(context: Context, macs: List<String>): Boolean {
        val packets = macs.mapNotNull { parseMac(it) }.map { packet(it) }
        if (packets.isEmpty()) return false
        var sent = false
        DatagramSocket().use { socket ->
            socket.broadcast = true
            for (address in LanInfo.broadcasts(context)) {
                for (data in packets) {
                    for (port in intArrayOf(9, 7)) {
                        if (runCatching { socket.send(DatagramPacket(data, data.size, address, port)) }.isSuccess) sent = true
                    }
                }
            }
        }
        return sent
    }
}

/** Datos de la red de casa: a dónde mandar algo para que lo reciban todos. */
object LanInfo {

    /** 255.255.255.255 y la difusión de la red wifi (p. ej. 192.168.1.255). */
    fun broadcasts(context: Context): List<InetAddress> {
        val result = mutableListOf(InetAddress.getByName("255.255.255.255"))
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val links = runCatching { connectivity?.getLinkProperties(connectivity.activeNetwork)?.linkAddresses }.getOrNull()
        links?.forEach { link ->
            val address = link.address
            if (address is Inet4Address) result += InetAddress.getByAddress(broadcastOf(address.address, link.prefixLength))
        }
        return result.distinct()
    }

    /** La dirección de difusión de [ip] con una máscara de [prefix] bits. */
    fun broadcastOf(ip: ByteArray, prefix: Int): ByteArray {
        val value = ip.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        val mask = if (prefix <= 0) 0 else -1 shl (32 - prefix.coerceAtMost(32))
        val broadcast = value or mask.inv()
        return ByteArray(4) { ((broadcast shr (24 - it * 8)) and 0xFF).toByte() }
    }
}
