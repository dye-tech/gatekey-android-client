package com.gatekey.client.data.repository

import com.gatekey.client.data.api.GatekeyApi
import com.gatekey.client.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val message: String, val code: Int? = null) : Result<Nothing>()
    data object Loading : Result<Nothing>()
}

@Singleton
class GatewayRepository @Inject constructor(
    private val api: GatekeyApi
) {
    private val _gateways = MutableStateFlow<List<Gateway>>(emptyList())
    val gateways: StateFlow<List<Gateway>> = _gateways.asStateFlow()

    private val _meshHubs = MutableStateFlow<List<MeshHub>>(emptyList())
    val meshHubs: StateFlow<List<MeshHub>> = _meshHubs.asStateFlow()

    private val _activeConnections = MutableStateFlow<Map<String, ActiveConnection>>(emptyMap())
    val activeConnections: StateFlow<Map<String, ActiveConnection>> = _activeConnections.asStateFlow()

    suspend fun fetchGateways(): Result<List<Gateway>> {
        return try {
            val response = api.getGateways()
            if (response.isSuccessful) {
                val gateways = response.body()?.gateways ?: emptyList()
                _gateways.value = gateways
                Result.Success(gateways)
            } else {
                Result.Error(
                    response.errorBody()?.string() ?: "Failed to fetch gateways",
                    response.code()
                )
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Network error")
        }
    }

    suspend fun fetchMeshHubs(): Result<List<MeshHub>> {
        return try {
            val response = api.getMeshHubs()
            if (response.isSuccessful) {
                val hubs = response.body()?.hubs ?: emptyList()
                _meshHubs.value = hubs
                Result.Success(hubs)
            } else {
                Result.Error(
                    response.errorBody()?.string() ?: "Failed to fetch mesh hubs",
                    response.code()
                )
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Network error")
        }
    }

    suspend fun generateConfig(gatewayId: String): Result<GeneratedConfig> {
        return try {
            val response = api.generateConfig(GenerateConfigRequest(gatewayId))
            if (response.isSuccessful) {
                response.body()?.let { Result.Success(it) }
                    ?: Result.Error("Empty response")
            } else {
                Result.Error(
                    response.errorBody()?.string() ?: "Failed to generate config",
                    response.code()
                )
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Network error")
        }
    }

    suspend fun generateMeshConfig(hubId: String): Result<GeneratedMeshConfig> {
        return try {
            val response = api.generateMeshConfig(GenerateMeshConfigRequest(hubId))
            if (response.isSuccessful) {
                response.body()?.let { Result.Success(it) }
                    ?: Result.Error("Empty response")
            } else {
                Result.Error(
                    response.errorBody()?.string() ?: "Failed to generate mesh config",
                    response.code()
                )
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Network error")
        }
    }

    suspend fun downloadConfig(configId: String): Result<String> {
        return try {
            val response = api.downloadConfig(configId)
            if (response.isSuccessful) {
                response.body()?.string()?.let { Result.Success(it) }
                    ?: Result.Error("Empty config")
            } else {
                Result.Error(
                    response.errorBody()?.string() ?: "Failed to download config",
                    response.code()
                )
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Network error")
        }
    }

    fun updateConnectionState(id: String, connection: ActiveConnection) {
        _activeConnections.value = _activeConnections.value.toMutableMap().apply {
            put(id, connection)
        }
    }

    fun removeConnection(id: String) {
        _activeConnections.value = _activeConnections.value.toMutableMap().apply {
            remove(id)
        }
    }

    fun getGatewayById(id: String): Gateway? {
        return _gateways.value.find { it.id == id }
    }

    fun getMeshHubById(id: String): MeshHub? {
        return _meshHubs.value.find { it.id == id }
    }
}
