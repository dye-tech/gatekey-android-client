package com.gatekey.client.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.gatekey.client.data.model.ConnectionState
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.StringReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages WireGuard VPN connections using the WireGuard Android tunnel library.
 */
@Singleton
class WireGuardServiceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WireGuardServiceManager"
        const val VPN_PERMISSION_REQUEST_CODE = 7003
        private const val STATS_POLL_INTERVAL_MS = 1000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var backend: Backend? = null
    private var currentTunnel: GateKeyTunnel? = null

    private val _isAvailable = MutableStateFlow(true)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _needsPermission = MutableStateFlow<Intent?>(null)
    val needsPermission: StateFlow<Intent?> = _needsPermission.asStateFlow()

    private val _bytesIn = MutableStateFlow(0L)
    val bytesIn: StateFlow<Long> = _bytesIn.asStateFlow()

    private val _bytesOut = MutableStateFlow(0L)
    val bytesOut: StateFlow<Long> = _bytesOut.asStateFlow()

    private val _localIp = MutableStateFlow<String?>(null)
    val localIp: StateFlow<String?> = _localIp.asStateFlow()

    private val _remoteIp = MutableStateFlow<String?>(null)
    val remoteIp: StateFlow<String?> = _remoteIp.asStateFlow()

    private val _remotePort = MutableStateFlow<String?>(null)
    val remotePort: StateFlow<String?> = _remotePort.asStateFlow()

    init {
        initializeBackend()
    }

    private fun initializeBackend() {
        try {
            backend = GoBackend(context)
            Log.d(TAG, "WireGuard GoBackend initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WireGuard backend", e)
            _isAvailable.value = false
        }
    }

    /**
     * Bind - ensure backend is initialized
     */
    fun bind() {
        if (backend == null) {
            initializeBackend()
        }
    }

    /**
     * Unbind - cleanup resources
     */
    fun unbind() {
        // Stop stats polling if running
        stopStatsPolling()
    }

    /**
     * Called when permission has been granted
     */
    fun onPermissionGranted() {
        _needsPermission.value = null
    }

    /**
     * Prepare VPN service - returns Intent if permission needed, null otherwise
     */
    fun prepareVpnService(): Intent? {
        return VpnService.prepare(context)
    }

    /**
     * Start VPN with WireGuard config (INI format)
     */
    fun startVpn(config: String): Boolean {
        return try {
            val backend = this.backend ?: run {
                Log.e(TAG, "Backend not initialized")
                _connectionState.value = ConnectionState.ERROR
                _statusMessage.value = "WireGuard backend not available"
                return false
            }

            _connectionState.value = ConnectionState.CONNECTING

            // Disconnect any existing tunnel first
            currentTunnel?.let { tunnel ->
                try {
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
                } catch (e: Exception) {
                    Log.d(TAG, "No existing tunnel to stop: ${e.message}")
                }
            }

            // Parse the WireGuard config
            val wgConfig = Config.parse(BufferedReader(StringReader(config)))

            // Extract connection info from config
            wgConfig.`interface`.addresses.firstOrNull()?.let { address ->
                _localIp.value = address.address.hostAddress
            }
            wgConfig.peers.firstOrNull()?.let { peer ->
                peer.endpoint.ifPresent { endpoint ->
                    _remoteIp.value = endpoint.host
                    _remotePort.value = endpoint.port.toString()
                }
            }

            // Create and start the tunnel
            val tunnel = GateKeyTunnel("gatekey")
            currentTunnel = tunnel

            backend.setState(tunnel, Tunnel.State.UP, wgConfig)

            _connectionState.value = ConnectionState.CONNECTED
            _statusMessage.value = "Connected"

            // Start polling for stats
            startStatsPolling()

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WireGuard VPN", e)
            _connectionState.value = ConnectionState.ERROR
            _statusMessage.value = e.message ?: "Failed to start WireGuard"
            false
        }
    }

    /**
     * Disconnect VPN
     */
    fun disconnect(): Boolean {
        return try {
            Log.d(TAG, "Disconnecting WireGuard VPN")
            _connectionState.value = ConnectionState.DISCONNECTING

            stopStatsPolling()

            val backend = this.backend
            val tunnel = currentTunnel

            if (backend != null && tunnel != null) {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
            }

            currentTunnel = null

            // Clear connection info
            _localIp.value = null
            _remoteIp.value = null
            _remotePort.value = null
            _bytesIn.value = 0L
            _bytesOut.value = 0L

            _connectionState.value = ConnectionState.DISCONNECTED
            _statusMessage.value = ""

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect WireGuard", e)
            false
        }
    }

    /**
     * Forcefully disconnect VPN
     */
    fun forceDisconnect(): Boolean {
        return try {
            Log.w(TAG, "Forcefully disconnecting WireGuard VPN")

            stopStatsPolling()

            val backend = this.backend
            val tunnel = currentTunnel

            if (backend != null && tunnel != null) {
                try {
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Error during force disconnect", e)
                }
            }

            currentTunnel = null

            // Force state update
            _connectionState.value = ConnectionState.DISCONNECTED
            _localIp.value = null
            _remoteIp.value = null
            _remotePort.value = null
            _bytesIn.value = 0L
            _bytesOut.value = 0L

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forcefully disconnect WireGuard", e)
            false
        }
    }

    private var statsPollingJob: kotlinx.coroutines.Job? = null

    private fun startStatsPolling() {
        stopStatsPolling()
        statsPollingJob = scope.launch {
            while (true) {
                try {
                    val backend = this@WireGuardServiceManager.backend
                    val tunnel = currentTunnel
                    if (backend != null && tunnel != null) {
                        val stats = backend.getStatistics(tunnel)
                        _bytesIn.value = stats.totalRx()
                        _bytesOut.value = stats.totalTx()
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Failed to get stats: ${e.message}")
                }
                delay(STATS_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopStatsPolling() {
        statsPollingJob?.cancel()
        statsPollingJob = null
    }

    /**
     * Internal tunnel implementation
     */
    private class GateKeyTunnel(private val tunnelName: String) : Tunnel {
        override fun getName(): String = tunnelName

        override fun onStateChange(newState: Tunnel.State) {
            Log.d(TAG, "Tunnel state changed to: $newState")
        }
    }
}
