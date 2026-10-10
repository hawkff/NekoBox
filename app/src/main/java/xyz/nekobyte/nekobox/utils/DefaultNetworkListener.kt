package xyz.nekobyte.nekobox.utils

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.ktx.Logs

/**
 * Shares one default-network callback between every listener in the process. All state changes
 * run under [lock], so a listener always sees events in the order the framework delivered them;
 * listeners run under the lock too and must not block on other threads.
 */
object DefaultNetworkListener {
    private val lock = Any()
    private val listeners = LinkedHashMap<Any, (Network?) -> Unit>()
    private var network: Network? = null
    private var fallback = false
    private val callbackRegistration = NetworkCallbackRegistration()

    fun start(key: Any, listener: (Network?) -> Unit) {
        synchronized(lock) {
            if (listeners.isEmpty()) register()
            listeners[key] = listener
            val current = network
            if (current != null) {
                listener(current)
            } else if (fallback) {
                listener(NekoBox.connectivity.activeNetwork)
            }
        }
    }

    fun stop(key: Any) {
        synchronized(lock) {
            if (listeners.remove(key) != null && listeners.isEmpty()) {
                network = null
                unregister()
            }
        }
    }

    // A listener may stop itself while being called, so iterate over a copy.
    private fun dispatch(value: Network?) = listeners.values.toList().forEach { it(value) }

    private object Callback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(lock) {
                DefaultNetworkListener.network = network
                dispatch(network)
            }
        }

        // Capability changes on the current network are passed on so link properties get re-read.
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            synchronized(lock) {
                if (DefaultNetworkListener.network == network) dispatch(network)
            }
        }

        override fun onLost(network: Network) {
            synchronized(lock) {
                if (DefaultNetworkListener.network == network) {
                    DefaultNetworkListener.network = null
                    dispatch(null)
                }
            }
        }
    }

    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        if (Build.VERSION.SDK_INT == 23) { // workarounds for OEM bugs
            removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            removeCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        }
    }.build()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return VPN interface since Android P DP1:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately, we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private fun register() {
        if (callbackRegistration.requiresFallback) {
            callbackRegistration.unregister {
                NekoBox.connectivity.unregisterNetworkCallback(Callback)
            }.onFailure {
                Logs.w("DefaultNetworkListener: retry unregister failed", it)
            }
        }
        if (callbackRegistration.requiresFallback) {
            fallback = true
            return
        }
        fallback = false
        callbackRegistration.register {
            when {
                Build.VERSION.SDK_INT >= 31 ->
                    NekoBox.connectivity.registerBestMatchingNetworkCallback(request, Callback, mainHandler)

                Build.VERSION.SDK_INT >= 28 ->
                    // Request the best non-VPN network instead of listening to all matches.
                    NekoBox.connectivity.requestNetwork(request, Callback, mainHandler)

                Build.VERSION.SDK_INT >= 26 ->
                    NekoBox.connectivity.registerDefaultNetworkCallback(Callback, mainHandler)

                Build.VERSION.SDK_INT >= 24 ->
                    NekoBox.connectivity.registerDefaultNetworkCallback(Callback)

                else -> {
                    NekoBox.connectivity.requestNetwork(request, Callback)
                    // known bug on API 23: https://stackoverflow.com/a/33509180/2245107
                }
            }
        }.onFailure {
            Logs.w(it)
            fallback = true
        }
    }

    private fun unregister() {
        callbackRegistration.unregister {
            NekoBox.connectivity.unregisterNetworkCallback(Callback)
        }.onFailure {
            fallback = true
            Logs.w("DefaultNetworkListener: failed to unregister network callback", it)
        }
    }
}
