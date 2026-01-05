package com.gatekey.client.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.gatekey.client.data.model.ActiveConnection
import com.gatekey.client.data.model.ConnectionState
import com.gatekey.client.data.model.ConnectionType
import com.gatekey.client.data.model.VpnProtocol
import com.gatekey.client.data.repository.GatewayRepository
import com.gatekey.client.data.repository.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gatewayRepository: GatewayRepository,
    private val openVpnServiceManager: OpenVpnServiceManager,
    private val wireGuardServiceManager: WireGuardServiceManager
) {
    companion object {
        private const val TAG = "VpnManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _vpnState = MutableStateFlow<VpnState>(VpnState.Idle)
    val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

    private val _activeConnections = MutableStateFlow<Map<String, ActiveConnection>>(emptyMap())
    val activeConnections: StateFlow<Map<String, ActiveConnection>> = _activeConnections.asStateFlow()

    // Expose connection details - combine from both service managers
    private val _localIp = MutableStateFlow<String?>(null)
    val localIp: StateFlow<String?> = _localIp.asStateFlow()

    private val _remoteIp = MutableStateFlow<String?>(null)
    val remoteIp: StateFlow<String?> = _remoteIp.asStateFlow()

    private val _remotePort = MutableStateFlow<String?>(null)
    val remotePort: StateFlow<String?> = _remotePort.asStateFlow()

    private val _bytesIn = MutableStateFlow(0L)
    val bytesIn: StateFlow<Long> = _bytesIn.asStateFlow()

    private val _bytesOut = MutableStateFlow(0L)
    val bytesOut: StateFlow<Long> = _bytesOut.asStateFlow()

    // Track current connection info for state updates
    private var currentConnectionId: String? = null
    private var currentConnectionName: String? = null
    private var currentConnectionType: ConnectionType? = null
    private var currentVpnProtocol: VpnProtocol? = null

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
        wireGuardServiceManager.bind()

        // Observe connection state changes from OpenVPN
        scope.launch {
            openVpnServiceManager.connectionState.collect { state ->
                if (currentVpnProtocol == VpnProtocol.OPENVPN || currentVpnProtocol == null) {
                    updateConnectionState(state)
                }
            }
        }

        // Observe connection state changes from WireGuard
        scope.launch {
            wireGuardServiceManager.connectionState.collect { state ->
                if (currentVpnProtocol == VpnProtocol.WIREGUARD) {
                    updateConnectionState(state)
                }
            }
        }

        // Observe connection details from OpenVPN
        scope.launch {
            openVpnServiceManager.localIp.collect { ip ->
                if (currentVpnProtocol == VpnProtocol.OPENVPN) _localIp.value = ip
            }
        }
        scope.launch {
            openVpnServiceManager.remoteIp.collect { ip ->
                if (currentVpnProtocol == VpnProtocol.OPENVPN) _remoteIp.value = ip
            }
        }
        scope.launch {
            openVpnServiceManager.remotePort.collect { port ->
                if (currentVpnProtocol == VpnProtocol.OPENVPN) _remotePort.value = port
            }
        }
        scope.launch {
            openVpnServiceManager.bytesIn.collect { bytes ->
                if (currentVpnProtocol == VpnProtocol.OPENVPN) _bytesIn.value = bytes
            }
        }
        scope.launch {
            openVpnServiceManager.bytesOut.collect { bytes ->
                if (currentVpnProtocol == VpnProtocol.OPENVPN) _bytesOut.value = bytes
            }
        }

        // Observe connection details from WireGuard
        scope.launch {
            wireGuardServiceManager.localIp.collect { ip ->
                if (currentVpnProtocol == VpnProtocol.WIREGUARD) _localIp.value = ip
            }
        }
        scope.launch {
            wireGuardServiceManager.remoteIp.collect { ip ->
                if (currentVpnProtocol == VpnProtocol.WIREGUARD) _remoteIp.value = ip
            }
        }
        scope.launch {
            wireGuardServiceManager.remotePort.collect { port ->
                if (currentVpnProtocol == VpnProtocol.WIREGUARD) _remotePort.value = port
            }
        }
        scope.launch {
            wireGuardServiceManager.bytesIn.collect { bytes ->
                if (currentVpnProtocol == VpnProtocol.WIREGUARD) _bytesIn.value = bytes
            }
        }
        scope.launch {
            wireGuardServiceManager.bytesOut.collect { bytes ->
                if (currentVpnProtocol == VpnProtocol.WIREGUARD) _bytesOut.value = bytes
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

        // Determine protocol from gateway type (wireguard or openvpn)
        val protocol = VpnProtocol.fromString(gateway.gatewayType ?: gateway.vpnProtocol)

        // Store connection info for state updates
        currentConnectionId = gatewayId
        currentConnectionName = gateway.name
        currentConnectionType = ConnectionType.GATEWAY
        currentVpnProtocol = protocol

        // Update active connections
        _activeConnections.value = mapOf(
            gatewayId to ActiveConnection(
                id = gatewayId,
                name = gateway.name,
                type = ConnectionType.GATEWAY,
                state = ConnectionState.CONNECTING,
                connectedAt = null,
                vpnProtocol = protocol
            )
        )

        return when (protocol) {
            VpnProtocol.WIREGUARD -> connectWireGuardGateway(gatewayId, gateway.name)
            VpnProtocol.OPENVPN -> connectOpenVpnGateway(gatewayId, gateway.name)
        }
    }

    private suspend fun connectOpenVpnGateway(gatewayId: String, gatewayName: String): Result<Unit> {
        // Generate config
        val configResult = gatewayRepository.generateConfig(gatewayId)
        if (configResult is Result.Error) {
            _vpnState.value = VpnState.Error(configResult.message)
            _activeConnections.value = emptyMap()
            return configResult
        }

        val generatedConfig = (configResult as Result.Success).data

        // Download config
        val downloadResult = gatewayRepository.downloadConfig(generatedConfig.id)
        if (downloadResult is Result.Error) {
            _vpnState.value = VpnState.Error(downloadResult.message)
            _activeConnections.value = emptyMap()
            return downloadResult
        }

        val ovpnConfig = (downloadResult as Result.Success).data

        // Start VPN via embedded OpenVPN library
        val started = openVpnServiceManager.startVpn(ovpnConfig)
        if (!started) {
            _vpnState.value = VpnState.Error("Failed to start OpenVPN connection")
            _activeConnections.value = emptyMap()
            return Result.Error("Failed to start OpenVPN connection")
        }

        return Result.Success(Unit)
    }

    private suspend fun connectWireGuardGateway(gatewayId: String, gatewayName: String): Result<Unit> {
        // Generate WireGuard config
        val configResult = gatewayRepository.generateWireGuardConfig(gatewayId)
        if (configResult is Result.Error) {
            _vpnState.value = VpnState.Error(configResult.message)
            _activeConnections.value = emptyMap()
            return configResult
        }

        val generatedConfig = (configResult as Result.Success).data

        // Download WireGuard config
        val downloadResult = gatewayRepository.downloadWireGuardConfig(generatedConfig.id)
        if (downloadResult is Result.Error) {
            _vpnState.value = VpnState.Error(downloadResult.message)
            _activeConnections.value = emptyMap()
            return downloadResult
        }

        val wgConfig = (downloadResult as Result.Success).data

        // Start VPN via WireGuard library
        val started = wireGuardServiceManager.startVpn(wgConfig)
        if (!started) {
            _vpnState.value = VpnState.Error("Failed to start WireGuard connection")
            _activeConnections.value = emptyMap()
            return Result.Error("Failed to start WireGuard connection")
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

        // Determine protocol from hub type (wireguard or openvpn)
        val protocol = VpnProtocol.fromString(hub.hubType ?: hub.vpnProtocol)

        // Store connection info for state updates
        currentConnectionId = hubId
        currentConnectionName = hubName
        currentConnectionType = ConnectionType.MESH_HUB
        currentVpnProtocol = protocol

        // Update active connections
        _activeConnections.value = mapOf(
            hubId to ActiveConnection(
                id = hubId,
                name = hubName,
                type = ConnectionType.MESH_HUB,
                state = ConnectionState.CONNECTING,
                connectedAt = null,
                vpnProtocol = protocol
            )
        )

        return when (protocol) {
            VpnProtocol.WIREGUARD -> connectWireGuardMeshHub(hubId, hubName)
            VpnProtocol.OPENVPN -> connectOpenVpnMeshHub(hubId, hubName)
        }
    }

    private suspend fun connectOpenVpnMeshHub(hubId: String, hubName: String): Result<Unit> {
        // Generate config - mesh endpoint returns config inline
        val configResult = gatewayRepository.generateMeshConfig(hubId)
        if (configResult is Result.Error) {
            _vpnState.value = VpnState.Error(configResult.message)
            _activeConnections.value = emptyMap()
            return configResult
        }

        val generatedMeshConfig = (configResult as Result.Success).data

        // Mesh config is returned directly in the response
        val ovpnConfig = generatedMeshConfig.config

        // Start VPN via embedded OpenVPN library
        val started = openVpnServiceManager.startVpn(ovpnConfig)
        if (!started) {
            _vpnState.value = VpnState.Error("Failed to start OpenVPN connection")
            _activeConnections.value = emptyMap()
            return Result.Error("Failed to start OpenVPN connection")
        }

        return Result.Success(Unit)
    }

    private suspend fun connectWireGuardMeshHub(hubId: String, hubName: String): Result<Unit> {
        // Generate WireGuard mesh config
        val configResult = gatewayRepository.generateWireGuardMeshConfig(hubId)
        if (configResult is Result.Error) {
            _vpnState.value = VpnState.Error(configResult.message)
            _activeConnections.value = emptyMap()
            return configResult
        }

        val generatedMeshConfig = (configResult as Result.Success).data

        // WireGuard mesh config is returned directly in the response
        val wgConfig = generatedMeshConfig.config

        // Start VPN via WireGuard library
        val started = wireGuardServiceManager.startVpn(wgConfig)
        if (!started) {
            _vpnState.value = VpnState.Error("Failed to start WireGuard connection")
            _activeConnections.value = emptyMap()
            return Result.Error("Failed to start WireGuard connection")
        }

        return Result.Success(Unit)
    }

    /**
     * Disconnect from VPN
     */
    fun disconnect() {
        _vpnState.value = VpnState.Disconnecting

        // Disconnect based on current protocol
        when (currentVpnProtocol) {
            VpnProtocol.WIREGUARD -> {
                wireGuardServiceManager.disconnect()
                // WireGuard disconnect is synchronous, update state immediately
                scope.launch {
                    kotlinx.coroutines.delay(500) // Brief delay to ensure cleanup
                    if (_vpnState.value is VpnState.Disconnecting) {
                        _vpnState.value = VpnState.Idle
                        _activeConnections.value = emptyMap()
                        clearConnectionState()
                    }
                }
            }
            VpnProtocol.OPENVPN, null -> {
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
                                clearConnectionState()
                            }
                        } else {
                            Log.d(TAG, "Disconnect timeout - VPN disconnected (state=$actualState), setting to Idle")
                            _vpnState.value = VpnState.Idle
                            _activeConnections.value = emptyMap()
                            clearConnectionState()
                        }
                    }
                }
            }
        }
    }

    private fun clearConnectionState() {
        currentConnectionId = null
        currentConnectionName = null
        currentConnectionType = null
        currentVpnProtocol = null
        _localIp.value = null
        _remoteIp.value = null
        _remotePort.value = null
        _bytesIn.value = 0L
        _bytesOut.value = 0L
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
                clearConnectionState()
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
                clearConnectionState()
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
                    clearConnectionState()
                }
            }
            ConnectionState.ERROR -> {
                val errorMessage = when (currentVpnProtocol) {
                    VpnProtocol.WIREGUARD -> wireGuardServiceManager.statusMessage.value.ifEmpty { "Connection failed" }
                    VpnProtocol.OPENVPN, null -> openVpnServiceManager.statusMessage.value.ifEmpty { "Connection failed" }
                }
                _vpnState.value = VpnState.Error(errorMessage)
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
        wireGuardServiceManager.unbind()
    }
}
