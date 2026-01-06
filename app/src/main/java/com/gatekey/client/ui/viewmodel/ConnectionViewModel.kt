package com.gatekey.client.ui.viewmodel

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatekey.client.VpnPermissionHandler
import com.gatekey.client.data.repository.GatewayRepository
import com.gatekey.client.data.repository.Result
import com.gatekey.client.data.repository.SettingsRepository
import com.gatekey.client.vpn.VpnManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrafficDataPoint(
    val bytesIn: Long,
    val bytesOut: Long,
    val timestamp: Long = System.currentTimeMillis()
)

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val gatewayRepository: GatewayRepository,
    private val vpnManager: VpnManager,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    companion object {
        private const val TAG = "ConnectionViewModel"
        const val VPN_PERMISSION_REQUEST_CODE = 1001
    }

    val gateways = gatewayRepository.gateways
    val meshHubs = gatewayRepository.meshHubs
    val activeConnections = vpnManager.activeConnections
    val vpnState = vpnManager.vpnState

    // Server URL from settings
    val serverUrl = settingsRepository.settings.map { it.serverUrl }

    // Dark mode setting
    val darkMode = settingsRepository.settings.map { it.darkMode }

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateDarkMode(enabled)
        }
    }

    // VPN connection info
    val localIp = vpnManager.localIp
    val remoteIp = vpnManager.remoteIp
    val remotePort = vpnManager.remotePort
    val bytesIn = vpnManager.bytesIn
    val bytesOut = vpnManager.bytesOut

    // Traffic history for graph (last 30 data points)
    private val _trafficHistory = MutableStateFlow<List<TrafficDataPoint>>(emptyList())
    val trafficHistory: StateFlow<List<TrafficDataPoint>> = _trafficHistory.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var pendingConnection: PendingConnection? = null

    data class PendingConnection(
        val id: String,
        val type: ConnectionType
    )

    enum class ConnectionType {
        GATEWAY,
        MESH_HUB
    }

    init {
        refreshData()
        startTrafficTracking()
    }

    private fun startTrafficTracking() {
        viewModelScope.launch {
            while (isActive) {
                val currentVpnState = vpnState.value
                if (currentVpnState is VpnManager.VpnState.Connected) {
                    val dataPoint = TrafficDataPoint(
                        bytesIn = bytesIn.value,
                        bytesOut = bytesOut.value
                    )
                    val currentHistory = _trafficHistory.value.toMutableList()
                    currentHistory.add(dataPoint)
                    // Keep last 30 data points
                    if (currentHistory.size > 30) {
                        currentHistory.removeAt(0)
                    }
                    _trafficHistory.value = currentHistory
                } else {
                    // Clear history when disconnected
                    if (_trafficHistory.value.isNotEmpty()) {
                        _trafficHistory.value = emptyList()
                    }
                }
                delay(1000) // Sample every second
            }
        }
    }

    fun refreshData() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            // Fetch gateways and mesh hubs in parallel
            val gatewaysResult = gatewayRepository.fetchGateways()
            val meshHubsResult = gatewayRepository.fetchMeshHubs()

            when {
                gatewaysResult is Result.Error -> {
                    _error.value = gatewaysResult.message
                }
                meshHubsResult is Result.Error -> {
                    _error.value = meshHubsResult.message
                }
            }

            _isLoading.value = false
        }
    }

    fun prepareVpn(activity: Activity): Intent? {
        return vpnManager.prepareVpn(activity)
    }

    fun connectToGateway(gatewayId: String, activity: Activity) {
        Log.d(TAG, "connectToGateway - gatewayId: $gatewayId")
        val vpnIntent = prepareVpn(activity)
        if (vpnIntent != null) {
            // Need VPN permission - register callback before showing permission dialog
            Log.d(TAG, "VPN permission needed, registering callback")
            pendingConnection = PendingConnection(gatewayId, ConnectionType.GATEWAY)

            // Register callback to handle permission result
            VpnPermissionHandler.setCallback { granted, act ->
                Log.d(TAG, "VPN permission callback received - granted: $granted")
                onVpnPermissionResult(granted, act)
            }

            activity.startActivityForResult(vpnIntent, VPN_PERMISSION_REQUEST_CODE)
            return
        }

        Log.d(TAG, "VPN permission already granted, connecting...")
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            when (val result = vpnManager.connectToGateway(gatewayId)) {
                is Result.Error -> {
                    Log.e(TAG, "Connect to gateway failed: ${result.message}")
                    _error.value = result.message
                }
                is Result.Success -> {
                    Log.d(TAG, "Connect to gateway succeeded")
                }
                is Result.Loading -> {}
            }

            _isLoading.value = false
        }
    }

    fun connectToMeshHub(hubId: String, activity: Activity) {
        Log.d(TAG, "connectToMeshHub - hubId: $hubId")
        val vpnIntent = prepareVpn(activity)
        if (vpnIntent != null) {
            // Need VPN permission - register callback before showing permission dialog
            Log.d(TAG, "VPN permission needed for mesh hub, registering callback")
            pendingConnection = PendingConnection(hubId, ConnectionType.MESH_HUB)

            // Register callback to handle permission result
            VpnPermissionHandler.setCallback { granted, act ->
                Log.d(TAG, "VPN permission callback received for mesh hub - granted: $granted")
                onVpnPermissionResult(granted, act)
            }

            activity.startActivityForResult(vpnIntent, VPN_PERMISSION_REQUEST_CODE)
            return
        }

        Log.d(TAG, "VPN permission already granted for mesh hub, connecting...")
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            when (val result = vpnManager.connectToMeshHub(hubId)) {
                is Result.Error -> {
                    Log.e(TAG, "Connect to mesh hub failed: ${result.message}")
                    _error.value = result.message
                }
                is Result.Success -> {
                    Log.d(TAG, "Connect to mesh hub succeeded")
                }
                is Result.Loading -> {}
            }

            _isLoading.value = false
        }
    }

    fun onVpnPermissionResult(granted: Boolean, activity: Activity) {
        Log.d(TAG, "onVpnPermissionResult - granted: $granted, pendingConnection: $pendingConnection")

        // Clear the callback to prevent duplicate calls
        VpnPermissionHandler.clearCallback()

        if (granted && pendingConnection != null) {
            val connection = pendingConnection!!
            pendingConnection = null // Clear before reconnecting to avoid loop

            Log.d(TAG, "VPN permission granted, proceeding with connection type: ${connection.type}")

            // Add a small delay to allow the VPN service to fully initialize after permission grant
            // This prevents TimeoutException from GoBackend when the service isn't ready
            viewModelScope.launch {
                Log.d(TAG, "Waiting for VPN service to initialize...")
                delay(500) // 500ms delay for service initialization
                Log.d(TAG, "Proceeding with connection after delay")

                when (connection.type) {
                    ConnectionType.GATEWAY -> {
                        connectToGateway(connection.id, activity)
                    }
                    ConnectionType.MESH_HUB -> {
                        connectToMeshHub(connection.id, activity)
                    }
                }
            }
        } else if (!granted) {
            Log.w(TAG, "VPN permission denied by user")
            _error.value = "VPN permission denied"
            pendingConnection = null
        } else {
            Log.w(TAG, "Permission granted but no pending connection")
            pendingConnection = null
        }
    }

    fun disconnect() {
        vpnManager.disconnect()
    }

    fun clearError() {
        _error.value = null
    }
}
