package com.controlxia.app.iot

import android.content.Context
import android.os.Build
import com.controlxia.app.diagnostics.AgentLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * Las teles Android y Google TV, por la red: vincular (una vez, con el código
 * que muestra la tele) y mandar teclas (prender, apagar, volumen, pausa) o
 * abrir apps. El mismo protocolo que la app de control remoto de Google.
 */
class AndroidTv(private val context: Context) {

    /** Una vinculación en curso: la tele muestra el código y espera que lo escribas. */
    class Pairing internal constructor(
        private val socket: SSLSocket,
        private val client: RSAPublicKey,
        private val server: X509Certificate,
    ) : Closeable {

        /** La huella del certificado de la tele, para guardarla. */
        val pin: String = ClientIdentity.fingerprint(server)

        /** null si se vinculó; si no, qué pasó. */
        suspend fun finish(code: String): String? = withContext(Dispatchers.IO) {
            val serverKey = server.publicKey as? RSAPublicKey ?: return@withContext "La tele usa una clave que no conozco."
            val alpha = AtvSecret.alpha(client, serverKey, code)
                ?: return@withContext "Ese código no coincide: fijate que sean los 6 caracteres de la tele (números y letras de la A a la F)."
            runCatching {
                ProtoFraming.write(socket.outputStream, Polo.secret(alpha))
                val reply = Polo.parse(ProtoFraming.read(socket.inputStream))
                when {
                    reply.kind == Polo.Kind.SECRET_ACK -> null
                    reply.status == Polo.STATUS_BAD_SECRET -> "La tele dice que el código no es correcto."
                    else -> "La tele no aceptó la vinculación (${reply.status})."
                }
            }.getOrElse { "Se cortó la conexión con la tele: ${it.message}" }.also { close() }
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    /** Conecta con la tele y le pide que muestre el código. */
    suspend fun startPairing(host: String): Pairing = withContext(Dispatchers.IO) {
        var server: X509Certificate? = null
        val socket = connect(host, PAIRING_PORT, pin = null) { server = it }
        try {
            socket.soTimeout = HANDSHAKE_MS
            val input = socket.inputStream
            val output = socket.outputStream
            ProtoFraming.write(output, Polo.pairingRequest("ControlXIA (${Build.MODEL})"))
            expect(Polo.parse(ProtoFraming.read(input)), Polo.Kind.PAIRING_ACK)
            ProtoFraming.write(output, Polo.options())
            expect(Polo.parse(ProtoFraming.read(input)), Polo.Kind.OPTIONS)
            ProtoFraming.write(output, Polo.configuration())
            expect(Polo.parse(ProtoFraming.read(input)), Polo.Kind.CONFIGURATION_ACK)
            // Ahora la tele muestra el código y espera: tiempo para escribirlo.
            socket.soTimeout = TYPING_MS
            val client = ClientIdentity.get(context).second.publicKey as RSAPublicKey
            Pairing(socket, client, server ?: socket.session.peerCertificates.first() as X509Certificate)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    /**
     * Conecta, saluda y hace [block] con la conexión lista. La conexión se
     * cierra al terminar (un instante después, para que lleguen las teclas).
     */
    suspend fun <T> remote(tv: HomeDevice, block: suspend (RemoteSession) -> T): T = withContext(Dispatchers.IO) {
        val socket = connect(tv.host, tv.port, pin = tv.pin)
        val session = RemoteSession(socket)
        try {
            if (!session.awaitReady(READY_MS)) throw IOException("la tele no terminó de saludar")
            block(session).also { delay(LINGER_MS) }
        } finally {
            session.close()
        }
    }

    private fun connect(host: String, port: Int, pin: String?, onServer: (X509Certificate) -> Unit = {}): SSLSocket {
        val factory = ClientIdentity.sslContext(context, pin, onServer).socketFactory
        val socket = factory.createSocket() as SSLSocket
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_MS)
            socket.soTimeout = HANDSHAKE_MS
            socket.startHandshake()
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
        return socket
    }

    private fun expect(reply: Polo.Reply, kind: Polo.Kind) {
        if (reply.kind != kind) throw IOException("la tele contestó ${reply.kind} (${reply.status})")
    }

    /** La conexión de control: contesta los "ping" de la tele y manda teclas y enlaces. */
    class RemoteSession internal constructor(private val socket: SSLSocket) : Closeable {

        /** Si la tele está prendida (lo dice al saludar); null si todavía no lo dijo. */
        @Volatile
        var isOn: Boolean? = null
            private set

        @Volatile
        private var features = RemoteMessages.FEATURES
        private val ready = CountDownLatch(1)
        private val lock = Any()

        init {
            socket.soTimeout = 0
            thread(isDaemon = true, name = "tele") { listen() }
        }

        private fun listen() {
            try {
                while (!socket.isClosed) {
                    when (val message = RemoteMessages.parse(ProtoFraming.read(socket.inputStream))) {
                        is RemoteMessages.Incoming.Configure -> {
                            features = RemoteMessages.FEATURES and message.features
                            send(RemoteMessages.configure(features))
                        }
                        RemoteMessages.Incoming.SetActive -> send(RemoteMessages.setActive(features))
                        is RemoteMessages.Incoming.Ping -> send(RemoteMessages.pingResponse(message.value))
                        is RemoteMessages.Incoming.Started -> {
                            isOn = message.on
                            ready.countDown()
                        }
                        RemoteMessages.Incoming.Error -> AgentLog.add("TELE contestó con un error")
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                if (!socket.isClosed) AgentLog.add("TELE se cortó la conexión: ${e.message}")
            } finally {
                ready.countDown()
            }
        }

        fun awaitReady(ms: Long): Boolean = ready.await(ms, TimeUnit.MILLISECONDS) && !socket.isClosed

        fun key(code: Int) = send(RemoteMessages.key(code))

        fun app(link: String) = send(RemoteMessages.appLink(link))

        private fun send(message: ByteArray) = synchronized(lock) { ProtoFraming.write(socket.outputStream, message) }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    companion object {
        const val PAIRING_PORT = 6467
        const val REMOTE_PORT = 6466
        private const val CONNECT_MS = 4_000
        private const val HANDSHAKE_MS = 10_000
        private const val TYPING_MS = 180_000
        private const val READY_MS = 6_000L
        private const val LINGER_MS = 400L
    }
}
