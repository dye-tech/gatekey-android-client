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

        currentConnectionId = null
        currentConnectionName = null
        currentConnectionType = null
        _activeConnections.value = emptyMap()
        _vpnState.value = VpnState.Idle
    }

    private fun updateConnectionState(state: ConnectionState) {
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
                _vpnState.value = VpnState.Idle
                _activeConnections.value = emptyMap()
                currentConnectionId = null
                currentConnectionName = null
                currentConnectionType = null
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
