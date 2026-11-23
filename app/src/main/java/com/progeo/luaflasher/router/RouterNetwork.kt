package com.progeo.luaflasher.router

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import com.progeo.teltonika.api.RouterException
import com.progeo.teltonika.tls.PinningTrustManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RouterWifiException(ssid: String) : RouterException(
    "The phone could not join the Wi-Fi \"$ssid\". Is the router on and close by? Allow the connection when Android asks.",
    "WifiNetworkSpecifier for $ssid unavailable",
)

class NoCableException : RouterException("No network cable found. Connect the router with a USB Ethernet adapter.")

/** The network to the router, and the router's address on it. */
data class RouterLink(val network: Network, val gateway: String?, val viaCable: Boolean)

/**
 * The path from the phone to the router. The router's Wi-Fi usually has no internet (no SIM yet),
 * so it is requested as a local-only network with [WifiNetworkSpecifier]: Android shows a
 * confirmation once, keeps the phone's internet on mobile data, and only sockets created through
 * the [Network] use the router. The router's HTTP client is bound to it ([httpClient]); the
 * backend keeps using the default network.
 */
class RouterNetwork(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    var lost: Boolean = false
        private set

    /** Joins [ssid]; suspends until Android connected or gave up (after [timeoutMs]). */
    suspend fun joinWifi(ssid: String, passphrase: String?, timeoutMs: Int = 60_000): RouterLink {
        release()
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).apply { if (passphrase != null) setWpa2Passphrase(passphrase) }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val network = suspendCancellableCoroutine { cont ->
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    lost = false
                    if (cont.isActive) cont.resume(network)
                }

                override fun onUnavailable() {
                    if (cont.isActive) cont.resumeWithException(RouterWifiException(ssid))
                }

                override fun onLost(network: Network) {
                    lost = true
                }
            }
            callback = cb
            lost = false
            cm.requestNetwork(request, cb, timeoutMs)
            cont.invokeOnCancellation { release() }
        }
        return RouterLink(network, gateway(network), viaCable = false)
    }

    /** A USB Ethernet adapter connected to the router (workbench mode). */
    suspend fun cable(): RouterLink {
        release()
        @Suppress("DEPRECATION")
        val network = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true }
            ?: throw NoCableException()
        return RouterLink(network, gateway(network), viaCable = true)
    }

    /** Leaves the router's Wi-Fi (Android drops a network nobody requests any more). */
    fun release() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }

    /** The router's address: the DHCP server or the gateway of the network (waits for DHCP). */
    private suspend fun gateway(network: Network): String? {
        repeat(20) {
            cm.getLinkProperties(network)?.let(::gatewayOf)?.let { return it }
            delay(250)
        }
        return null
    }

    private fun gatewayOf(lp: LinkProperties): String? {
        // The DHCP server is only reported from Android 11 on; the gateway is the same router.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (lp.dhcpServerAddress as? Inet4Address)?.hostAddress?.let { return it }
        }
        return lp.routes.firstOrNull { it.gateway is Inet4Address && !it.gateway!!.isAnyLocalAddress }?.gateway?.hostAddress
    }

    companion object {
        /** An HTTP client that reaches the router only through [link] and trusts only [trust]. */
        fun httpClient(base: OkHttpClient, link: RouterLink, trust: PinningTrustManager): OkHttpClient =
            trust.applyTo(base.newBuilder())
                .socketFactory(link.network.socketFactory)
                .dns(object : Dns {
                    override fun lookup(hostname: String) = link.network.getAllByName(hostname).toList()
                })
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
    }
}
