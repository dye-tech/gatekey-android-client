package com.gatekey.client.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Build
import android.os.IBinder
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.StringReader
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

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
        private const val SERVICE_WARMUP_TIMEOUT_MS = 5000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var backend: Backend? = null
    private var currentTunnel: GateKeyTunnel? = null

    // Track if VPN service has been warmed up
    @Volatile
    private var serviceWarmedUp = false

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
            // Note: Background warmup removed - Android doesn't allow starting services from background
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WireGuard backend", e)
            _isAvailable.value = false
        }
    }

    // Service connection for binding to VPN service
    private var vpnServiceBound = false
    private var vpnServiceBinder: IBinder? = null

    private val vpnServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "VPN service connected via binding")
            vpnServiceBinder = service
            vpnServiceBound = true
            serviceWarmedUp = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "VPN service disconnected")
            vpnServiceBinder = null
            vpnServiceBound = false
            serviceWarmedUp = false
        }
    }

    /**
     * Bind to the VPN service to keep it alive and ready.
     * Binding creates a persistent connection that prevents the service from being killed.
     */
    private fun bindVpnService(): Boolean {
        if (vpnServiceBound) {
            Log.d(TAG, "VPN service already bound")
            return true
        }

        return try {
            val vpnServiceClass = Class.forName("com.wireguard.android.backend.GoBackend\$VpnService")
            val intent = Intent(context, vpnServiceClass)

            // Start as foreground service first, then bind
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            // Bind to keep the service alive
            val bound = context.bindService(intent, vpnServiceConnection, Context.BIND_AUTO_CREATE)
            Log.d(TAG, "VPN service bind request: $bound")
            bound
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bind VPN service: ${e.message}", e)
            false
        }
    }

    /**
     * Unbind from the VPN service.
     */
    private fun unbindVpnService() {
        if (vpnServiceBound) {
            try {
                context.unbindService(vpnServiceConnection)
                vpnServiceBound = false
                vpnServiceBinder = null
                Log.d(TAG, "VPN service unbound")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unbind VPN service: ${e.message}")
            }
        }
    }

    /**
     * Explicitly warm up the VPN service by binding to it.
     * Call this after VPN permission is granted to ensure the service is ready.
     */
    suspend fun warmupVpnService() {
        if (serviceWarmedUp) {
            Log.d(TAG, "VPN service already warmed up")
            return
        }

        try {
            Log.d(TAG, "Starting VPN service warmup via binding...")
            bindVpnService()
            // Wait for service to connect
            delay(2000)
            Log.d(TAG, "VPN service warmup complete, bound=$vpnServiceBound")
        } catch (e: Exception) {
            Log.w(TAG, "VPN service warmup failed: ${e.message}", e)
        }
    }

    /**
     * Ensure VPN service is ready before attempting connection.
     * Uses binding to create a persistent connection.
     */
    private fun ensureServiceReady() {
        Log.d(TAG, "Ensuring VPN service is ready, currently bound=$vpnServiceBound")

        if (!vpnServiceBound) {
            bindVpnService()
            // Give the service time to connect
            Thread.sleep(1500)
        }

        Log.d(TAG, "VPN service ready check complete, bound=$vpnServiceBound")
    }

    /**
     * Start the VPN service as a foreground service.
     * This is needed to ensure the service stays alive during connection attempts.
     */
    private fun startVpnServiceForeground() {
        try {
            val vpnServiceClass = Class.forName("com.wireguard.android.backend.GoBackend\$VpnService")
            val intent = Intent(context, vpnServiceClass)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "Started VPN service as foreground service")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start VPN service foreground: ${e.message}")
        }
    }

    /**
     * Ensure VPN service is started before attempting connection.
     */
    private fun ensureServiceStarted() {
        Log.d(TAG, "Ensuring VPN service is started")
        startVpnServiceForeground()
        // Give the service time to start
        Thread.sleep(500)
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
     * Called when permission has been granted.
     * Triggers VPN service warmup in background so it's ready for connection.
     */
    fun onPermissionGranted() {
        _needsPermission.value = null

        // Immediately start warming up the VPN service now that we have permission
        scope.launch {
            Log.d(TAG, "Permission granted, starting VPN service warmup...")
            warmupVpnService()
        }
    }

    /**
     * Prepare VPN service - returns Intent if permission needed, null otherwise
     */
    fun prepareVpnService(): Intent? {
        return VpnService.prepare(context)
    }

    /**
     * Start VPN with WireGuard config (INI format)
     * Uses retry logic for reliability when VPN service is slow to initialize
     */
    fun startVpn(config: String): Boolean {
        // Let the WireGuard library manage its own service lifecycle
        // We just retry if it times out during initialization
        return startVpnWithRetry(config, maxRetries = 8)
    }

    private fun startVpnWithRetry(config: String, maxRetries: Int, attempt: Int = 1): Boolean {
        return try {
            Log.d(TAG, "Starting WireGuard VPN (attempt $attempt/$maxRetries), config length: ${config.length}")

            val backend = this.backend ?: run {
                Log.e(TAG, "Backend not initialized")
                _connectionState.value = ConnectionState.ERROR
                _statusMessage.value = "WireGuard backend not available"
                return false
            }

            _connectionState.value = ConnectionState.CONNECTING
            _statusMessage.value = if (attempt > 1) "Connecting... (attempt $attempt)" else "Connecting..."

            // Disconnect any existing tunnel first (only on first attempt)
            if (attempt == 1) {
                currentTunnel?.let { tunnel ->
                    try {
                        Log.d(TAG, "Stopping existing tunnel")
                        backend.setState(tunnel, Tunnel.State.DOWN, null)
                    } catch (e: Exception) {
                        Log.d(TAG, "No existing tunnel to stop: ${e.message}")
                    }
                }
            }

            // Parse the WireGuard config (only on first attempt to avoid repeated parsing)
            val wgConfig = if (attempt == 1) {
                Log.d(TAG, "Parsing WireGuard config...")
                try {
                    Config.parse(BufferedReader(StringReader(config))).also { cfg ->
                        Log.d(TAG, "Config parsed successfully")

                        // Extract connection info from config
                        cfg.`interface`.addresses.firstOrNull()?.let { address ->
                            _localIp.value = address.address.hostAddress
                            Log.d(TAG, "Local IP: ${address.address.hostAddress}")
                        }
                        cfg.peers.firstOrNull()?.let { peer ->
                            peer.endpoint.ifPresent { endpoint ->
                                _remoteIp.value = endpoint.host
                                _remotePort.value = endpoint.port.toString()
                                Log.d(TAG, "Peer endpoint: ${endpoint.host}:${endpoint.port}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse WireGuard config: ${e.message}")
                    throw Exception("Config parse error: ${e.message}", e)
                }
            } else {
                // Reparse on retries - simpler than caching
                Config.parse(BufferedReader(StringReader(config)))
            }

            // Create and start the tunnel
            Log.d(TAG, "Creating tunnel...")
            val tunnel = GateKeyTunnel("gatekey")
            currentTunnel = tunnel

            Log.d(TAG, "Setting tunnel state to UP...")
            backend.setState(tunnel, Tunnel.State.UP, wgConfig)
            Log.d(TAG, "Tunnel state set successfully")

            _connectionState.value = ConnectionState.CONNECTED
            _statusMessage.value = "Connected"
            serviceWarmedUp = true  // Mark as warmed up on success

            // Start polling for stats
            startStatsPolling()

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WireGuard VPN: ${e.javaClass.simpleName}: ${e.message}", e)
            e.cause?.let { cause ->
                Log.e(TAG, "Caused by: ${cause.javaClass.simpleName}: ${cause.message}")
            }

            // Check if this is a TimeoutException (VPN service not ready) and we can retry
            val isTimeout = e.cause is java.util.concurrent.TimeoutException ||
                    e.message?.contains("TimeoutException") == true

            if (isTimeout && attempt < maxRetries) {
                // Wait and retry - the WireGuard library manages its own service lifecycle
                val waitTime = 2000L
                Log.w(TAG, "VPN service timeout, retrying in ${waitTime}ms (attempt ${attempt + 1}/$maxRetries)...")
                _statusMessage.value = "Initializing VPN... (attempt ${attempt + 1})"

                // Wait for the VPN service to initialize
                Thread.sleep(waitTime)

                return startVpnWithRetry(config, maxRetries, attempt + 1)
            }

            // Reinitialize backend after connection failure to ensure clean state for next attempt
            Log.d(TAG, "Reinitializing backend after connection failure")
            currentTunnel = null
            this.backend = GoBackend(context)

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

            // Stop the VPN service explicitly to ensure clean state
            try {
                val vpnServiceClass = Class.forName("com.wireguard.android.backend.GoBackend\$VpnService")
                val intent = Intent(context, vpnServiceClass)
                context.stopService(intent)
                Log.d(TAG, "Explicitly stopped VPN service")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop VPN service: ${e.message}")
            }

            // Give VPN service time to fully terminate before reinitializing backend
            Thread.sleep(500)

            // Reinitialize backend after disconnect to ensure clean state for next connect
            Log.d(TAG, "Reinitializing backend for clean state on next connect")
            this.backend = GoBackend(context)
            serviceWarmedUp = false

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

            // Stop the VPN service explicitly
            try {
                val vpnServiceClass = Class.forName("com.wireguard.android.backend.GoBackend\$VpnService")
                val intent = Intent(context, vpnServiceClass)
                context.stopService(intent)
                Log.d(TAG, "Force stopped VPN service")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop VPN service: ${e.message}")
            }

            // Give VPN service time to terminate
            Thread.sleep(500)

            // Reinitialize backend after force disconnect to ensure clean state
            Log.d(TAG, "Reinitializing backend after force disconnect")
            this.backend = GoBackend(context)
            serviceWarmedUp = false

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
