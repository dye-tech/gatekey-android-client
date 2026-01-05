package com.gatekey.client.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.gatekey.client.data.model.ActiveConnection
import com.gatekey.client.data.model.ConnectionState
import com.gatekey.client.data.model.ConnectionType
import com.gatekey.client.data.repository.GatewayRepository
import com.gatekey.client.data.repository.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gatewayRepository: GatewayRepository,
    private val openVpnServiceManager: OpenVpnServiceManager
) {
    companion object {
        private const val TAG = "VpnManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _vpnState = MutableStateFlow<VpnState>(VpnState.Idle)
    val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

    private val _activeConnections = MutableStateFlow<Map<String, ActiveConnection>>(emptyMap())
    val activeConnections: StateFlow<Map<String, ActiveConnection>> = _activeConnections.asStateFlow()

    // Expose connection details from OpenVPN
    val localIp: StateFlow<String?> = openVpnServiceManager.localIp
    val remoteIp: StateFlow<String?> = openVpnServiceManager.remoteIp
    val remotePort: StateFlow<String?> = openVpnServiceManager.remotePort
    val bytesIn: StateFlow<Long> = openVpnServiceManager.bytesIn
    val bytesOut: StateFlow<Long> = openVpnServiceManager.bytesOut

    // Track current connection info for state updates
    private var currentConnectionId: String? = null
    private var currentConnectionName: String? = null
    private var currentConnectionType: ConnectionType? = null

    sealed class VpnState {
        data object Idle : VpnState()
        data object PermissionRequired : VpnState()
        data class Connecting(val gatewayId: String, val gatewayName: String) : VpnState()
        data class Connected(val gatewayId: String, val gatewayName: String) : VpnState()
        data object Disconnecting : VpnState()
        data class Error(val message: String) : VpnState()
    }

    init {
        // Register for status updates
        openVpnServiceManager.bind()

        // Observe connection state changes from OpenVPN
        scope.launch {
            openVpnServiceManager.connectionState.collect { state ->
                updateConnectionState(state)
            }
        }
    }

    /**
     * Check if VPN permission is granted
     */
    fun prepareVpn(activity: Activity): Intent? {
        return VpnService.prepare(activity)
    }

    /**
     * Connect to a gateway
     */
    suspend fun connectToGateway(gatewayId: String): Result<Unit> {
        val gateway = gatewayRepository.getGatewayById(gatewayId)
            ?: return Result.Error("Gateway not found")

        _vpnState.value = VpnState.Connecting(gatewayId, gateway.name)

        // Store connection info for state updates
        currentConnectionId = gatewayId
        currentConnectionName = gateway.name
        currentConnectionType = ConnectionType.GATEWAY

        // Generate config
        val configResult = gatewayRepository.generateConfig(gatewayId)
        if (configResult is Result.Error) {
            _vpnState.value = VpnState.Error(configResult.message)
            return configResult
        }

        val generatedConfig = (configResult as Result.Success).data

        // Download config
        val downloadResult = gatewayRepository.downloadConfig(generatedConfig.id)
        if (downloadResult is Result.Error) {
            _vpnState.value = VpnState.Error(downloadResult.message)
            return downloadResult
        }

        val ovpnConfig = (downloadResult as Result.Success).data

        // Update active connections
        _activeConnections.value = mapOf(
            gatewayId to ActiveConnection(
                id = gatewayId,
                name = gateway.name,
                type = ConnectionType.GATEWAY,
                state = ConnectionState.CONNECTING,
                connectedAt = null
            )
        )

        // Start VPN via embedded OpenVPN library
        val started = openVpnServiceManager.startVpn(ovpnConfig)
        if (!started) {
            _vpnState.value = VpnState.Error("Failed to start VPN connection")
            _activeConnections.value = emptyMap()
            return Result.Error("Failed to start VPN connection")
        }

        return Result.Success(Unit)
    }

    /**
     * Connect to a mesh hub
     */
    suspend fun connectToMeshHub(hubId: String): Result<Unit> {
        val hub = gatewayRepository.getMeshHubById(hubId)
            ?: return Result.Error("Mesh hub not found")

        val hubName = hub.name ?: "Mesh Hub"
        _vpnState.value = VpnState.Connecting(hubId, hubName)

        // Store connection info for state updates
        currentConnectionId = hubId
        currentConnectionName = hubName
        currentConnectionType = ConnectionType.MESH_HUB

        // Generate config - mesh endpoint returns config inline
        val configResult = gatewayRepository.generateMeshConfig(hubId)
        if (configResult is Result.Error) {
            _vpnState.value = VpnState.Error(configResult.message)
            return configResult
        }

        val generatedMeshConfig = (configResult as Result.Success).data

        // Mesh config is returned directly in the response
        val ovpnConfig = generatedMeshConfig.config

        // Update active connections
        _activeConnections.value = mapOf(
            hubId to ActiveConnection(
                id = hubId,
                name = hubName,
                type = ConnectionType.MESH_HUB,
                state = ConnectionState.CONNECTING,
                connectedAt = null
            )
        )

        // Start VPN via embedded OpenVPN library
        val started = openVpnServiceManager.startVpn(ovpnConfig)
        if (!started) {
            _vpnState.value = VpnState.Error("Failed to start VPN connection")
            _activeConnections.value = emptyMap()
            return Result.Error("Failed to start VPN connection")
        }

        return Result.Success(Unit)
    }

    /**
     * Disconnect from VPN
     */
    fun disconnect() {
        _vpnState.value = VpnState.Disconnecting

        openVpnServiceManager.disconnect()

        // Start a timeout to check disconnect status
        // If VPN is still connected after timeout, retry disconnect
        scope.launch {
            kotlinx.coroutines.delay(3000) // Wait 3 seconds for OpenVPN to disconnect
            if (_vpnState.value is VpnState.Disconnecting) {
                // Check if VPN actually disconnected by checking OpenVPN's state
                val actualState = openVpnServiceManager.connectionState.value
                if (actualState == ConnectionState.CONNECTED || actualState == ConnectionState.CONNECTING) {
                    Log.w(TAG, "Disconnect timeout but VPN still active (state=$actualState), retrying forceful disconnect")
                    // VPN didn't actually disconnect - try forceful disconnect
                    openVpnServiceManager.forceDisconnect()

                    // Wait another 2 seconds for forceful disconnect
                    kotlinx.coroutines.delay(2000)

                    val finalState = openVpnServiceManager.connectionState.value
                    if (finalState == ConnectionState.CONNECTED || finalState == ConnectionState.CONNECTING) {
                        Log.e(TAG, "Forceful disconnect failed, VPN still active")
                        _vpnState.value = VpnState.Error("Failed to disconnect VPN. Please try again or restart the app.")
                    } else {
                        Log.d(TAG, "Forceful disconnect succeeded")
                        _vpnState.value = VpnState.Idle
                        _activeConnections.value = emptyMap()
                        currentConnectionId = null
                        currentConnectionName = null
                        currentConnectionType = null
                    }
                } else {
                    Log.d(TAG, "Disconnect timeout - VPN disconnected (state=$actualState), setting to Idle")
                    _vpnState.value = VpnState.Idle
                    _activeConnections.value = emptyMap()
                    currentConnectionId = null
                    currentConnectionName = null
                    currentConnectionType = null
                }
            }
        }
    }

    private fun updateConnectionState(state: ConnectionState) {
        // If we're actively disconnecting, only process DISCONNECTED state
        // Ignore intermediate states like CONNECTING that OpenVPN may report during shutdown
        if (_vpnState.value is VpnState.Disconnecting) {
            if (state == ConnectionState.DISCONNECTED) {
                Log.d(TAG, "VPN disconnected, setting state to Idle. Active connections before: ${_activeConnections.value.size}, vpnState before: ${_vpnState.value}")
                _vpnState.value = VpnState.Idle
                _activeConnections.value = emptyMap()
                Log.d(TAG, "Active connections after clear: ${_activeConnections.value.size}, vpnState after: ${_vpnState.value}")
                currentConnectionId = null
                currentConnectionName = null
                currentConnectionType = null
            }
            // Ignore all other states while disconnecting
            return
        }

        // Handle disconnected state even if we don't have connection info
        // This can happen if the app was restarted while VPN was connected
        if (state == ConnectionState.DISCONNECTED) {
            if (_vpnState.value !is VpnState.Idle && _vpnState.value !is VpnState.Connecting) {
                Log.d(TAG, "VPN disconnected (no connection info), setting state to Idle")
                _vpnState.value = VpnState.Idle
                _activeConnections.value = emptyMap()
                currentConnectionId = null
                currentConnectionName = null
                currentConnectionType = null
            }
            return
        }

        val id = currentConnectionId ?: return
        val name = currentConnectionName ?: return
        val type = currentConnectionType ?: return

        val currentConnection = _activeConnections.value[id]
        if (currentConnection != null) {
            _activeConnections.value = mapOf(
                id to currentConnection.copy(
                    state = state,
                    connectedAt = if (state == ConnectionState.CONNECTED) {
                        System.currentTimeMillis()
                    } else {
                        currentConnection.connectedAt
                    }
                )
            )
        }

        when (state) {
            ConnectionState.CONNECTED -> {
                _vpnState.value = VpnState.Connected(id, name)
            }
            ConnectionState.DISCONNECTED -> {
                // Don't clear connection state if we're actively connecting to a new VPN
                // This prevents the DISCONNECTED from old VPN cleanup from breaking new connection
                if (_vpnState.value !is VpnState.Connecting) {
                    _vpnState.value = VpnState.Idle
                    _activeConnections.value = emptyMap()
                    currentConnectionId = null
                    currentConnectionName = null
                    currentConnectionType = null
                }
            }
            ConnectionState.ERROR -> {
                _vpnState.value = VpnState.Error(openVpnServiceManager.statusMessage.value.ifEmpty { "Connection failed" })
            }
            ConnectionState.CONNECTING -> {
                _vpnState.value = VpnState.Connecting(id, name)
            }
            ConnectionState.DISCONNECTING -> {
                _vpnState.value = VpnState.Disconnecting
            }
        }
    }

    fun cleanup() {
        openVpnServiceManager.unbind()
    }
}
