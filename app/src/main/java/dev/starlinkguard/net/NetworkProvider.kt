package dev.starlinkguard.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import javax.net.SocketFactory

/**
 * Keeps track of two networks at once, because this app needs both.
 *
 * The dish must be reached over Wi-Fi specifically. Android decides the Starlink LAN has no
 * internet — which is often true once the dish is unplugged, and sometimes true anyway — and
 * routes ordinary traffic to cellular, so a poll sent on the default network never arrives.
 * Binding the socket to the Wi-Fi [Network] is what makes the poll actually go out of the
 * Wi-Fi interface.
 *
 * The webhook needs the opposite: a network that really can reach the internet. If the dish
 * has just been carried off, its Wi-Fi is gone too, and the alert has to leave over cellular.
 */
class NetworkProvider(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    var wifiNetwork: Network? = null
        private set

    @Volatile
    var internetNetwork: Network? = null
        private set

    /**
     * A stable identifier for the Wi-Fi the phone is on, or null when it is on none.
     *
     * Uses the network handle rather than the SSID on purpose: reading an SSID requires
     * ACCESS_FINE_LOCATION, and this only needs to answer "is this the same network as
     * before", which the handle does without asking the user for anything. A reconnect yields
     * a new handle, which errs towards forgetting rather than towards a false alarm.
     */
    val wifiNetworkId: String?
        get() = wifiNetwork?.networkHandle?.toString()

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetwork = network
        }

        override fun onLost(network: Network) {
            if (wifiNetwork == network) wifiNetwork = null
        }
    }

    private val internetCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            internetNetwork = network
        }

        override fun onLost(network: Network) {
            if (internetNetwork == network) internetNetwork = null
        }
    }

    private var registered = false

    fun start() {
        if (registered) return
        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val internetRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        runCatching {
            connectivityManager.registerNetworkCallback(wifiRequest, wifiCallback)
            connectivityManager.registerNetworkCallback(internetRequest, internetCallback)
            registered = true
        }.onFailure { Log.w(TAG, "could not register network callbacks", it) }
    }

    fun stop() {
        if (!registered) return
        runCatching { connectivityManager.unregisterNetworkCallback(wifiCallback) }
        runCatching { connectivityManager.unregisterNetworkCallback(internetCallback) }
        registered = false
        wifiNetwork = null
        internetNetwork = null
    }

    /** Socket factory bound to Wi-Fi, or `null` to fall back to the default routing. */
    fun wifiSocketFactory(): SocketFactory? = wifiNetwork?.socketFactory

    /** Socket factory bound to something that can actually reach the internet. */
    fun internetSocketFactory(): SocketFactory? = internetNetwork?.socketFactory

    private companion object {
        const val TAG = "NetworkProvider"
    }
}
