package com.controlxia.app.brain

import java.net.URI

/**
 * Las direcciones a las que se puede hablar sin cifrar (http): solo un
 * servidor en tu propia red, como Ollama o LM Studio en la PC de casa. Para
 * cualquier otra dirección hace falta https, así la clave y lo que decís no
 * viajan a la vista por internet.
 */
object LocalNetwork {

    /** true si [url] es https, o http a la red local. */
    fun isAllowed(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        return when (uri.scheme?.lowercase()) {
            "https" -> true
            "http" -> isLocalHost(uri.host.orEmpty())
            else -> false
        }
    }

    fun isLocalHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h == "::1" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) {
            return true
        }
        val parts = h.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        val (a, b) = parts[0] to parts[1]
        return a == 10 || a == 127 ||
            (a == 192 && b == 168) ||
            (a == 172 && b in 16..31) ||
            // Tailscale y otras VPN entre tus equipos.
            (a == 100 && b in 64..127) ||
            (a == 169 && b == 254)
    }

    const val NOT_ALLOWED =
        "Por seguridad, las direcciones http (sin cifrar) solo se aceptan en tu red de casa " +
            "(por ejemplo http://192.168.1.10:11434/v1). Para internet usá https."
}
