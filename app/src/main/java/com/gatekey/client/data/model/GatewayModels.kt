package com.gatekey.client.data.model

import com.google.gson.annotations.SerializedName

/**
 * VPN protocol type
 */
enum class VpnProtocol {
    OPENVPN,
    WIREGUARD;

    companion object {
        fun fromString(value: String?): VpnProtocol {
            return when (value?.lowercase()) {
                "wireguard" -> WIREGUARD
                else -> OPENVPN  // Default to OpenVPN
            }
        }
    }

    fun displayName(): String {
        return when (this) {
            OPENVPN -> "OpenVPN"
            WIREGUARD -> "WireGuard"
        }
    }
}

/**
 * Gateway information
 */
data class Gateway(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("hostname") val hostname: String,
    @SerializedName("publicIp") val publicIp: String? = null,
    @SerializedName("vpnPort") val vpnPort: Int? = null,
    @SerializedName("vpnProtocol") val vpnProtocol: String? = null,
    @SerializedName("gatewayType") val gatewayType: String? = null,
    @SerializedName("isActive") val isActive: Boolean = true,
    @SerializedName("lastHeartbeat") val lastHeartbeat: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("location") val location: String? = null
) {
    fun getProtocol(): VpnProtocol = VpnProtocol.fromString(gatewayType ?: vpnProtocol)
}

data class GatewaysResponse(
    @SerializedName("gateways") val gateways: List<Gateway>
)

/**
 * Mesh hub information
 */
data class MeshHub(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String? = null,
    @SerializedName("publicEndpoint") val publicEndpoint: String? = null,
    @SerializedName("vpnPort") val vpnPort: Int? = null,
    @SerializedName("vpnProtocol") val vpnProtocol: String? = null,
    @SerializedName("hubType") val hubType: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("lastHeartbeat") val lastHeartbeat: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("networks") val networks: List<String>? = null
) {
    fun getProtocol(): VpnProtocol = VpnProtocol.fromString(hubType ?: vpnProtocol)
}

data class MeshHubsResponse(
    @SerializedName("hubs") val hubs: List<MeshHub>
)

/**
 * Config generation request
 */
data class GenerateConfigRequest(
    @SerializedName("gateway_id") val gatewayId: String,
    @SerializedName("cli_callback_url") val cliCallbackUrl: String? = null
)

data class GenerateMeshConfigRequest(
    @SerializedName("hubid") val hubId: String
)

/**
 * Config generation response (for gateways)
 */
data class GeneratedConfig(
    @SerializedName("id") val id: String,
    @SerializedName("file_name") val fileName: String,
    @SerializedName("gateway_name") val gatewayName: String? = null,
    @SerializedName("hub_name") val hubName: String? = null,
    @SerializedName("expires_at") val expiresAt: String,
    @SerializedName("download_url") val downloadUrl: String
)

/**
 * Mesh config generation response (includes config inline)
 */
data class GeneratedMeshConfig(
    @SerializedName("id") val id: String,
    @SerializedName("config") val config: String,
    @SerializedName("hubname") val hubName: String? = null,
    @SerializedName("expiresAt") val expiresAt: String? = null
)

/**
 * Connection state for a gateway/hub
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

/**
 * Active connection information
 */
data class ActiveConnection(
    val id: String,
    val name: String,
    val type: ConnectionType,
    val state: ConnectionState,
    val connectedAt: Long? = null,
    val localIp: String? = null,
    val remoteIp: String? = null,
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
    val errorMessage: String? = null,
    val vpnProtocol: VpnProtocol = VpnProtocol.OPENVPN
)

enum class ConnectionType {
    GATEWAY,
    MESH_HUB
}

/**
 * User's generated configs list
 */
data class UserConfigsResponse(
    @SerializedName("configs") val configs: List<UserConfig>
)

data class UserConfig(
    @SerializedName("id") val id: String,
    @SerializedName("gateway_name") val gatewayName: String? = null,
    @SerializedName("hub_name") val hubName: String? = null,
    @SerializedName("file_name") val fileName: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("expires_at") val expiresAt: String,
    @SerializedName("is_revoked") val isRevoked: Boolean = false,
    @SerializedName("downloaded_at") val downloadedAt: String? = null
)
