package com.controlxia.app.iot

import android.content.Context
import com.controlxia.app.brain.LocalNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID

/** Habla con el programita de ControlXIA en las compus con Windows ([PcProtocol]). */
class PcAgent(private val context: Context) {

    data class Found(val name: String, val host: String, val port: Int, val macs: List<String>)

    /** Las compus con el programita andando en la red. */
    suspend fun discover(timeoutMs: Long = DISCOVER_MS): List<Found> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, Found>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 300
                val payload = PcProtocol.DISCOVER.toByteArray()
                for (address in LanInfo.broadcasts(context)) {
                    runCatching { socket.send(DatagramPacket(payload, payload.size, address, PcProtocol.UDP_PORT)) }
                }
                val until = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < until) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val host = packet.address?.hostAddress ?: continue
                        val announce = PcProtocol.parseAnnounce(String(packet.data, 0, packet.length, Charsets.UTF_8)) ?: continue
                        found[host] = Found(announce.name, host, announce.port, announce.macs)
                    } catch (_: SocketTimeoutException) {
                        // Seguir esperando hasta el tope.
                    }
                }
            }
        }
        found.values.toList()
    }

    /** Le pide algo a la compu. La respuesta, o el error en palabras. */
    suspend fun send(host: String, port: Int, code: String, cmd: String, arg: String = ""): Result<PcProtocol.Answer> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Solo a la red de casa: el código no tiene que viajar a otro lado.
                require(LocalNetwork.isLocalHost(host)) { "esa dirección no es de tu red" }
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), CONNECT_MS)
                    socket.soTimeout = READ_MS
                    val line = PcProtocol.request(code, cmd, arg, System.currentTimeMillis() / 1000, UUID.randomUUID().toString())
                    socket.getOutputStream().apply {
                        write((line + "\n").toByteArray(Charsets.UTF_8))
                        flush()
                    }
                    val reply = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                        ?: error("la compu no contestó")
                    PcProtocol.parseAnswer(reply) ?: error("la compu contestó algo raro")
                }
            }
        }

    suspend fun send(pc: HomeDevice, cmd: String, arg: String = ""): Result<PcProtocol.Answer> =
        send(pc.host, pc.port, pc.code.orEmpty(), cmd, arg)

    private companion object {
        const val DISCOVER_MS = 1_500L
        const val CONNECT_MS = 2_500
        const val READ_MS = 5_000
    }
}
