package com.gatekey.client.data.api

import com.gatekey.client.data.model.*
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Gatekey API interface for Retrofit
 */
interface GatekeyApi {

    // ============= Authentication =============

    @GET("api/v1/auth/providers")
    suspend fun getAuthProviders(): Response<AuthProvidersResponse>

    @GET("api/v1/auth/session")
    suspend fun getSession(): Response<SessionResponse>

    @GET("api/v1/auth/cli/login")
    suspend fun initiateCliLogin(
        @Query("callback") callbackUrl: String,
        @Query("cli_state") cliState: String
    ): Response<ResponseBody>

    @GET("api/v1/auth/cli/complete")
    suspend fun completeCliLogin(
        @Query("state") state: String
    ): Response<CliCompleteResponse>

    @GET("api/v1/auth/api-key/validate")
    suspend fun validateApiKey(
        @Header("Authorization") apiKey: String
    ): Response<ApiKeyValidationResponse>

    @POST("api/v1/auth/logout")
    suspend fun logout(): Response<Unit>

    @POST("api/v1/auth/refresh")
    suspend fun refreshToken(): Response<TokenResponse>

    @POST("api/v1/auth/local/login")
    suspend fun localLogin(
        @Body request: LocalLoginRequest
    ): Response<LoginResponse>

    // ============= Gateways =============

    @GET("api/v1/gateways")
    suspend fun getGateways(): Response<GatewaysResponse>

    @GET("api/v1/gateways/{id}")
    suspend fun getGateway(
        @Path("id") gatewayId: String
    ): Response<Gateway>

    // ============= Mesh Hubs =============

    @GET("api/v1/mesh/hubs")
    suspend fun getMeshHubs(): Response<MeshHubsResponse>

    // ============= VPN Configs =============

    @POST("api/v1/configs/generate")
    suspend fun generateConfig(
        @Body request: GenerateConfigRequest
    ): Response<GeneratedConfig>

    @POST("api/v1/mesh/generate-config")
    suspend fun generateMeshConfig(
        @Body request: GenerateMeshConfigRequest
    ): Response<GeneratedMeshConfig>

    @GET("api/v1/configs/download/{configId}")
    suspend fun downloadConfig(
        @Path("configId") configId: String
    ): Response<ResponseBody>

    @GET("api/v1/configs/list")
    suspend fun listConfigs(): Response<UserConfigsResponse>

    @PUT("api/v1/configs/{id}/revoke")
    suspend fun revokeConfig(
        @Path("id") configId: String
    ): Response<Unit>
}
