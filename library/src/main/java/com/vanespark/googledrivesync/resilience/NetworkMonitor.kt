package com.vanespark.googledrivesync.resilience

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.vanespark.googledrivesync.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Network connection state
 */
data class NetworkState(
    /**
     * Whether any network connection is available
     */
    val isConnected: Boolean,

    /**
     * Whether the connection is unmetered (e.g., WiFi)
     */
    val isUnmetered: Boolean,

    /**
     * Whether the connection is WiFi
     */
    val isWifi: Boolean,

    /**
     * Whether the connection is cellular
     */
    val isCellular: Boolean,

    /**
     * Whether the connection is roaming
     */
    val isRoaming: Boolean
) {
    companion object {
        val DISCONNECTED = NetworkState(
            isConnected = false,
            isUnmetered = false,
            isWifi = false,
            isCellular = false,
            isRoaming = false
        )
    }
}

/**
 * Network policy for sync operations
 */
enum class NetworkPolicy {
    /**
     * Any network connection allowed
     */
    ANY,

    /**
     * Only unmetered connections (WiFi, etc.)
     */
    UNMETERED_ONLY,

    /**
     * Only WiFi connections
     */
    WIFI_ONLY,

    /**
     * Any connection except roaming
     */
    NOT_ROAMING
}

/**
 * Monitors network connectivity and provides state updates.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkState = MutableStateFlow(getCurrentNetworkState())

    /**
     * Current network state as observable flow
     */
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    /**
     * Check if network is currently available
     */
    val isConnected: Boolean get() = _networkState.value.isConnected

    /**
     * Check if current network is unmetered
     */
    val isUnmetered: Boolean get() = _networkState.value.isUnmetered

    /**
     * Check if current network is WiFi
     */
    val isWifi: Boolean get() = _networkState.value.isWifi

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateNetworkState()
        }

        override fun onLost(network: Network) {
            updateNetworkState()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            updateNetworkState()
        }
    }

    private var isRegistered = false

    /**
     * Start monitoring network changes
     */
    fun startMonitoring() {
        if (isRegistered) return

        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            connectivityManager.registerNetworkCallback(request, networkCallback)
            isRegistered = true
            updateNetworkState()
            Log.d(Constants.TAG, "Network monitoring started")
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Failed to start network monitoring", e)
        }
    }

    /**
     * Stop monitoring network changes
     */
    fun stopMonitoring() {
        if (!isRegistered) return

        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            isRegistered = false
            Log.d(Constants.TAG, "Network monitoring stopped")
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Failed to stop network monitoring", e)
        }
    }

    /**
     * Get current network state
     */
    fun getCurrentNetworkState(): NetworkState {
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }

        return if (capabilities != null) {
            NetworkState(
                isConnected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                isUnmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                isRoaming = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
            )
        } else {
            NetworkState.DISCONNECTED
        }
    }

    private fun updateNetworkState() {
        _networkState.value = getCurrentNetworkState()
        Log.d(Constants.TAG, "Network state updated: ${_networkState.value}")
    }

    /**
     * Check if network meets the specified policy
     */
    fun meetsPolicy(policy: NetworkPolicy): Boolean {
        val state = _networkState.value

        if (!state.isConnected) return false

        return when (policy) {
            NetworkPolicy.ANY -> true
            NetworkPolicy.UNMETERED_ONLY -> state.isUnmetered
            NetworkPolicy.WIFI_ONLY -> state.isWifi
            NetworkPolicy.NOT_ROAMING -> !state.isRoaming
        }
    }

    /**
     * Wait for network that meets policy (as Flow)
     */
    fun waitForNetwork(policy: NetworkPolicy): Flow<NetworkState> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val state = getCurrentNetworkState()
                if (meetsPolicy(policy)) {
                    trySend(state)
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val state = getCurrentNetworkState()
                if (meetsPolicy(policy)) {
                    trySend(state)
                }
            }
        }

        // Check current state first
        if (meetsPolicy(policy)) {
            send(getCurrentNetworkState())
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }
}
