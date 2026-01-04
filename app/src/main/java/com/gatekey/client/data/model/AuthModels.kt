package com.gatekey.client.data.model

import com.google.gson.annotations.SerializedName

/**
 * Authentication provider information
 */
data class AuthProvider(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("type") val type: String, // "oidc", "saml", "local"
    @SerializedName("display_name") val displayName: String? = null,
    @SerializedName("icon_url") val iconUrl: String? = null
)

data class AuthProvidersResponse(
    @SerializedName("providers") val providers: List<AuthProvider>
)

/**
 * User session information
 */
data class UserSession(
    @SerializedName("id") val id: String,
    @SerializedName("email") val email: String,
    @SerializedName("name") val name: String,
    @SerializedName("groups") val groups: List<String>,
    @SerializedName("is_admin") val isAdmin: Boolean,
    @SerializedName("expires_at") val expiresAt: String
)

data class SessionResponse(
    @SerializedName("user") val user: UserSession
)

/**
 * Login initiation response
 */
data class LoginInitResponse(
    @SerializedName("redirect_url") val redirectUrl: String,
    @SerializedName("state") val state: String? = null
)

/**
 * Token response after successful authentication
 */
data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String = "Bearer",
    @SerializedName("expires_at") val expiresAt: String,
    @SerializedName("user") val user: UserSession? = null
)

/**
 * API key validation response
 */
data class ApiKeyValidationResponse(
    @SerializedName("valid") val valid: Boolean,
    @SerializedName("user") val user: UserSession? = null
)

/**
 * Local token storage model
 */
data class StoredToken(
    val accessToken: String,
    val expiresAt: Long,
    val userEmail: String,
    val userName: String,
    val serverUrl: String
)

/**
 * CLI login response (for mobile-adapted flow)
 */
data class CliLoginResponse(
    @SerializedName("login_url") val loginUrl: String,
    @SerializedName("state") val state: String
)

/**
 * CLI complete response
 */
data class CliCompleteResponse(
    @SerializedName("status") val status: String,
    @SerializedName("token") val token: String? = null,
    @SerializedName("error") val error: String? = null
)

/**
 * Local login request
 */
data class LocalLoginRequest(
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String
)

/**
 * Login response (for local/API login)
 */
data class LoginResponse(
    @SerializedName("token") val token: String? = null,
    @SerializedName("access_token") val accessToken: String? = null,
    @SerializedName("expires_at") val expiresAt: String? = null,
    @SerializedName("user") val user: UserSession? = null,
    @SerializedName("error") val error: String? = null
)
