package com.gatekey.client.ui.viewmodel

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatekey.client.data.repository.GatewayRepository
import com.gatekey.client.data.repository.Result
import com.gatekey.client.vpn.VpnManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val gatewayRepository: GatewayRepository,
    private val vpnManager: VpnManager
) : ViewModel() {

    val gateways = gatewayRepository.gateways
    val meshHubs = gatewayRepository.meshHubs
    val activeConnections = vpnManager.activeConnections
    val vpnState = vpnManager.vpnState

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
        val vpnIntent = prepareVpn(activity)
        if (vpnIntent != null) {
            // Need VPN permission
            pendingConnection = PendingConnection(gatewayId, ConnectionType.GATEWAY)
            activity.startActivityForResult(vpnIntent, VPN_PERMISSION_REQUEST_CODE)
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            when (val result = vpnManager.connectToGateway(gatewayId)) {
                is Result.Error -> {
                    _error.value = result.message
                }
                is Result.Success -> {
                    // Connected
                }
                is Result.Loading -> {}
            }

            _isLoading.value = false
        }
    }

    fun connectToMeshHub(hubId: String, activity: Activity) {
        val vpnIntent = prepareVpn(activity)
        if (vpnIntent != null) {
            // Need VPN permission
            pendingConnection = PendingConnection(hubId, ConnectionType.MESH_HUB)
            activity.startActivityForResult(vpnIntent, VPN_PERMISSION_REQUEST_CODE)
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            when (val result = vpnManager.connectToMeshHub(hubId)) {
                is Result.Error -> {
                    _error.value = result.message
                }
                is Result.Success -> {
                    // Connected
                }
                is Result.Loading -> {}
            }

            _isLoading.value = false
        }
    }

    fun onVpnPermissionResult(granted: Boolean, activity: Activity) {
        if (granted && pendingConnection != null) {
            when (pendingConnection!!.type) {
                ConnectionType.GATEWAY -> {
                    connectToGateway(pendingConnection!!.id, activity)
                }
                ConnectionType.MESH_HUB -> {
                    connectToMeshHub(pendingConnection!!.id, activity)
                }
            }
        } else if (!granted) {
            _error.value = "VPN permission denied"
        }
        pendingConnection = null
    }

    fun disconnect() {
        vpnManager.disconnect()
    }

    fun clearError() {
        _error.value = null
    }

    companion object {
        const val VPN_PERMISSION_REQUEST_CODE = 1001
    }
}
