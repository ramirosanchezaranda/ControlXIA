package com.controlxia.app.iot

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import com.controlxia.app.SecurePrefs
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Cómo se presenta ControlXIA ante las teles: una clave y un certificado
 * propios, creados una vez y guardados cifrados. La tele los recuerda al
 * vincularla, y nosotros recordamos el certificado de la tele (su huella):
 * si cambia, no se habla con ella (podría ser otro aparato haciéndose pasar).
 */
object ClientIdentity {

    private const val PREFS = "home_identity"
    private const val KEY = "key"
    private const val CERT = "cert"
    private val PASSWORD = "controlxia".toCharArray()

    private var cached: Pair<PrivateKey, X509Certificate>? = null

    @Synchronized
    fun get(context: Context): Pair<PrivateKey, X509Certificate> {
        cached?.let { return it }
        val prefs = SecurePrefs.get(context, PREFS)
        val stored = runCatching {
            val key = prefs.getString(KEY, null) ?: return@runCatching null
            val cert = prefs.getString(CERT, null) ?: return@runCatching null
            val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(key, Base64.NO_WRAP)))
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(Base64.decode(cert, Base64.NO_WRAP).inputStream()) as X509Certificate
            privateKey to certificate
        }.getOrNull()
        val identity = stored ?: run {
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val certificate = SelfSignedCert.create(keys, "ControlXIA")
            prefs.edit()
                .putString(KEY, Base64.encodeToString(keys.private.encoded, Base64.NO_WRAP))
                .putString(CERT, Base64.encodeToString(certificate.encoded, Base64.NO_WRAP))
                .apply()
            keys.private to certificate
        }
        cached = identity
        return identity
    }

    /**
     * TLS con nuestro certificado. [pin]: la huella que tiene que tener la tele
     * (null al vincular: se acepta y se avisa con [onServer] para guardarla).
     */
    fun sslContext(context: Context, pin: String?, onServer: (X509Certificate) -> Unit = {}): SSLContext {
        val (key, cert) = get(context)
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("client", key, PASSWORD, arrayOf(cert))
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, PASSWORD) }
        return SSLContext.getInstance("TLS").apply {
            init(keys.keyManagers, arrayOf(PinningTrust(pin, onServer)), SecureRandom())
        }
    }

    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    /**
     * Las teles usan certificados propios (no de una autoridad): se confía en
     * el que se vio al vincular, y en ningún otro.
     */
    @SuppressLint("CustomX509TrustManager")
    private class PinningTrust(private val pin: String?, private val onServer: (X509Certificate) -> Unit) : X509TrustManager {
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val server = chain?.firstOrNull() ?: throw CertificateException("La tele no mandó su certificado")
            if (pin != null && fingerprint(server) != pin) {
                throw CertificateException("El certificado de la tele cambió: volvé a vincularla")
            }
            onServer(server)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
