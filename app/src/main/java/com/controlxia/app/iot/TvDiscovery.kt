package com.controlxia.app.iot

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import kotlin.coroutines.resume

/**
 * Busca las teles Android y Google TV de la red: se anuncian solas (mDNS) como
 * "_androidtvremote2._tcp", el servicio del control remoto.
 */
class TvDiscovery(context: Context) {

    data class Found(val name: String, val host: String, val port: Int)

    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    suspend fun scan(timeoutMs: Long = SCAN_MS): List<Found> {
        val manager = nsd ?: return emptyList()
        val seen = mutableListOf<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(seen) { if (seen.none { it.serviceName == info.serviceName }) seen += info }
            }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        runCatching { manager.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { return emptyList() }
        try {
            delay(timeoutMs)
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
        // De a una: los Android viejos no resuelven dos a la vez.
        val services = synchronized(seen) { seen.toList() }
        return services.mapNotNull { resolve(manager, it) }
    }

    @Suppress("DEPRECATION") // resolveService sigue andando y es lo único que hay antes de Android 14.
    private suspend fun resolve(manager: NsdManager, info: NsdServiceInfo): Found? = withTimeoutOrNull(RESOLVE_MS) {
        suspendCancellableCoroutine { cont ->
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = address(serviceInfo)
                    if (cont.isActive) cont.resume(host?.let { Found(serviceInfo.serviceName, it, AndroidTv.REMOTE_PORT) })
                }
            }
            runCatching { manager.resolveService(info, listener) }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }

    @Suppress("DEPRECATION")
    private fun address(info: NsdServiceInfo): String? {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) info.hostAddresses else listOfNotNull(info.host)
        return (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull())?.hostAddress
    }

    private companion object {
        const val SERVICE = "_androidtvremote2._tcp."
        const val SCAN_MS = 3_500L
        const val RESOLVE_MS = 3_000L
    }
}
