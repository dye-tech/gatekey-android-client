package com.gatekey.client.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.gatekey.client.data.api.DynamicBaseUrlInterceptor
import com.gatekey.client.data.api.GatekeyApi
import com.gatekey.client.data.model.AuthProvider
import com.gatekey.client.data.model.UserSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.net.URLEncoder
import com.gatekey.client.SsoStateManager
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AuthRepository"

@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: GatekeyApi,
    private val tokenRepository: TokenRepository,
    private val settingsRepository: SettingsRepository,
    private val dynamicBaseUrlInterceptor: DynamicBaseUrlInterceptor
) {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _currentUser = MutableStateFlow<UserSession?>(null)
    val currentUser: StateFlow<UserSession?> = _currentUser.asStateFlow()

    private val _providers = MutableStateFlow<List<AuthProvider>>(emptyList())
    val providers: StateFlow<List<AuthProvider>> = _providers.asStateFlow()

    private var pendingLoginState: String? = null

    sealed class AuthState {
        data object Unknown : AuthState()
        data object LoggedOut : AuthState()
        data object LoggingIn : AuthState()
        data class LoggedIn(val user: UserSession) : AuthState()
        data class Error(val message: String) : AuthState()
    }

    suspend fun checkAuthState() {
        if (tokenRepository.hasValidToken()) {
            // Try to get session info
            try {
                val response = api.getSession()
                if (response.isSuccessful) {
                    response.body()?.user?.let { user ->
                        _currentUser.value = user
                        _authState.value = AuthState.LoggedIn(user)
                        return
                    }
                }
            } catch (e: Exception) {
                // Token might be invalid
            }
        }
        _authState.value = AuthState.LoggedOut
    }

    suspend fun fetchProviders(): Result<List<AuthProvider>> {
        return try {
            val response = api.getAuthProviders()
            if (response.isSuccessful) {
                val providers = response.body()?.providers ?: emptyList()
                _providers.value = providers
                Result.Success(providers)
            } else {
                Result.Error(
                    response.errorBody()?.string() ?: "Failed to fetch providers",
                    response.code()
                )
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Network error", exception = e)
        }
    }

    /**
     * Fetch providers after updating the server URL in the interceptor.
     * This ensures API calls go to the correct server.
     */
    suspend fun fetchProvidersForUrl(serverUrl: String): Result<List<AuthProvider>> {
        // Normalize the URL
        val normalizedUrl = if (serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) {
            serverUrl
        } else {
            "https://$serverUrl"
        }

        // Update the interceptor before making the request
        dynamicBaseUrlInterceptor.updateServerUrl(normalizedUrl)

        return fetchProviders()
    }

    suspend fun initiateLogin(serverUrl: String, provider: AuthProvider? = null): Result<String> {
        // Clear any existing token before re-login to avoid stale state
        if (_authState.value is AuthState.LoggedIn || tokenRepository.hasValidToken()) {
            Log.d(TAG, "Clearing existing session before SSO re-login")
            tokenRepository.clearToken()
            _currentUser.value = null
        }

        _authState.value = AuthState.LoggingIn

        // Save server URL and update the interceptor cache
        settingsRepository.updateServerUrl(serverUrl)
        dynamicBaseUrlInterceptor.updateServerUrl(serverUrl)

        // Generate state for OAuth - always generate a new state for each login attempt
        pendingLoginState = UUID.randomUUID().toString()

        // Construct login URL with mobile callback - properly URL encode the callback
        val callbackUrl = URLEncoder.encode("gatekey://callback", "UTF-8")

        // If a specific provider is provided, use provider-specific endpoint
        val loginUrl = if (provider != null) {
            when (provider.type.lowercase()) {
                "oidc" -> "$serverUrl/api/v1/auth/oidc/login?provider=${provider.name}&callback=$callbackUrl&cli_state=$pendingLoginState"
                "saml" -> "$serverUrl/api/v1/auth/saml/login?provider=${provider.name}&callback=$callbackUrl&cli_state=$pendingLoginState"
                else -> "$serverUrl/api/v1/auth/cli/login?callback=$callbackUrl&cli_state=$pendingLoginState"
            }
        } else {
            "$serverUrl/api/v1/auth/cli/login?callback=$callbackUrl&cli_state=$pendingLoginState"
        }

        Log.d(TAG, "Initiating login with URL: $loginUrl, state: $pendingLoginState, provider: ${provider?.name}")

        return Result.Success(loginUrl)
    }

    fun openLoginInBrowser(url: String) {
        // Mark that we're opening browser for SSO
        SsoStateManager.isWaitingForSso = true

        Log.d(TAG, "Opening external browser for SSO: $url")

        // Use external browser instead of Chrome Custom Tabs
        // Custom Tabs can cause immediate redirect issues with deep links
        // External browser runs as a separate process and properly waits for user auth
        val browserIntent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            Uri.parse(url)
        ).apply {
            // FLAG_ACTIVITY_NEW_TASK: Required when starting from non-Activity context
            // FLAG_ACTIVITY_NO_HISTORY: Don't keep the browser in the back stack after redirect
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(browserIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open browser: ${e.message}", e)
            // If no browser is available, throw an error
            throw RuntimeException("No browser available to complete SSO login")
        }
    }

    fun resetAuthState() {
        _authState.value = AuthState.LoggedOut
    }

    /**
     * Handle direct token response from SSO callback.
     * The GateKey server sends the token directly in the callback URL.
     */
    suspend fun handleDirectToken(
        token: String,
        email: String?,
        name: String?,
        expiresIn: String?
    ): Result<UserSession> {
        Log.d(TAG, "Handling direct token - email: $email, name: $name, expiresIn: $expiresIn")

        return try {
            // Calculate expiration time
            val expiresAt = if (expiresIn != null) {
                System.currentTimeMillis() + (expiresIn.toLongOrNull() ?: 86400L) * 1000
            } else {
                System.currentTimeMillis() + (24 * 60 * 60 * 1000) // Default 24 hours
            }

            // Get server URL from settings
            val serverUrl = settingsRepository.settings.first().serverUrl

            // Save the token
            tokenRepository.saveToken(
                accessToken = token,
                expiresAt = expiresAt,
                userEmail = email ?: "",
                userName = name ?: email ?: "",
                serverUrl = serverUrl
            )

            // Create user session object
            val user = UserSession(
                id = "",
                email = email ?: "",
                name = name ?: email ?: "",
                groups = emptyList(),
                isAdmin = false,
                expiresAt = ""
            )

            _currentUser.value = user
            _authState.value = AuthState.LoggedIn(user)
            Log.d(TAG, "SSO login successful for user: ${user.email}")

            Result.Success(user)
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Failed to save token"
            Log.e(TAG, "Error handling direct token: $errorMsg", e)
            _authState.value = AuthState.Error(errorMsg)
            Result.Error(errorMsg)
        }
    }

    suspend fun handleOAuthCallback(code: String?, state: String?, error: String?): Result<UserSession> {
        Log.d(TAG, "Handling OAuth callback - code: $code, state: $state, error: $error, pendingState: $pendingLoginState")

        if (error != null) {
            Log.e(TAG, "OAuth error: $error")
            _authState.value = AuthState.Error(error)
            pendingLoginState = null
            return Result.Error(error)
        }

        // SECURITY: Strict state validation to prevent CSRF attacks
        // The state parameter MUST be present and match our pending state
        if (state == null) {
            val msg = "Security error: Missing OAuth state parameter"
            Log.e(TAG, msg)
            _authState.value = AuthState.Error(msg)
            pendingLoginState = null
            return Result.Error(msg)
        }

        if (pendingLoginState == null) {
            val msg = "Security error: No pending login state - possible replay attack"
            Log.e(TAG, msg)
            _authState.value = AuthState.Error(msg)
            return Result.Error(msg)
        }

        // Verify state matches exactly - reject on mismatch
        if (state != pendingLoginState) {
            val msg = "Security error: OAuth state mismatch - possible CSRF attack"
            Log.e(TAG, "State mismatch - received: $state, expected: $pendingLoginState")
            _authState.value = AuthState.Error(msg)
            pendingLoginState = null
            return Result.Error(msg)
        }

        val stateToUse = state

        // Complete the login with retry logic (server might need time to process)
        return try {
            var lastError: String? = null
            var attempts = 0
            val maxAttempts = 3
            val delayMs = 1000L

            while (attempts < maxAttempts) {
                attempts++
                Log.d(TAG, "Completing CLI login, attempt $attempts with state: $stateToUse")

                val response = api.completeCliLogin(stateToUse)
                if (response.isSuccessful) {
                    val body = response.body()
                    Log.d(TAG, "CLI complete response: status=${body?.status}, token=${body?.token != null}, error=${body?.error}")

                    when {
                        body?.token != null -> {
                            // Parse expiration (default to 24 hours from now)
                            val expiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000)

                            // Get user info
                            val sessionResponse = api.getSession()
                            val user = sessionResponse.body()?.user

                            if (user != null) {
                                val serverUrl = settingsRepository.settings.first().serverUrl
                                tokenRepository.saveToken(
                                    accessToken = body.token,
                                    expiresAt = expiresAt,
                                    userEmail = user.email,
                                    userName = user.name,
                                    serverUrl = serverUrl
                                )
                                _currentUser.value = user
                                _authState.value = AuthState.LoggedIn(user)
                                Log.d(TAG, "Login successful for user: ${user.email}")
                                return Result.Success(user)
                            } else {
                                lastError = "Failed to get user info"
                            }
                        }
                        body?.status == "pending" -> {
                            // Server hasn't finished processing, wait and retry
                            Log.d(TAG, "Login pending, waiting before retry...")
                            delay(delayMs)
                            continue
                        }
                        body?.error != null -> {
                            lastError = body.error
                            break
                        }
                        else -> {
                            lastError = "Login incomplete"
                        }
                    }
                } else {
                    lastError = response.errorBody()?.string() ?: "Login failed (${response.code()})"
                    Log.e(TAG, "CLI complete failed: $lastError")
                    break
                }
            }

            val finalError = lastError ?: "Login failed after $maxAttempts attempts"
            _authState.value = AuthState.Error(finalError)
            Result.Error(finalError)
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Network error"
            Log.e(TAG, "Exception during OAuth callback: $errorMsg", e)
            _authState.value = AuthState.Error(errorMsg)
            Result.Error(errorMsg)
        } finally {
            pendingLoginState = null
        }
    }

    suspend fun loginWithApiKey(serverUrl: String, apiKey: String): Result<UserSession> {
        _authState.value = AuthState.LoggingIn

        // Save server URL and update the interceptor cache
        settingsRepository.updateServerUrl(serverUrl)
        dynamicBaseUrlInterceptor.updateServerUrl(serverUrl)

        return try {
            val authHeader = if (apiKey.startsWith("gk_")) {
                "Bearer $apiKey"
            } else {
                "Bearer gk_$apiKey"
            }

            val response = api.validateApiKey(authHeader)
            if (response.isSuccessful && response.body()?.valid == true) {
                val user = response.body()?.user
                if (user != null) {
                    // Store API key as token
                    val expiresAt = System.currentTimeMillis() + (365L * 24 * 60 * 60 * 1000) // 1 year

                    tokenRepository.saveToken(
                        accessToken = apiKey,
                        expiresAt = expiresAt,
                        userEmail = user.email,
                        userName = user.name,
                        serverUrl = serverUrl
                    )
                    _currentUser.value = user
                    _authState.value = AuthState.LoggedIn(user)
                    Result.Success(user)
                } else {
                    _authState.value = AuthState.Error("Invalid API key response")
                    Result.Error("Invalid API key response")
                }
            } else {
                _authState.value = AuthState.Error("Invalid API key")
                Result.Error("Invalid API key", response.code())
            }
        } catch (e: Exception) {
            val error = e.message ?: "Network error"
            _authState.value = AuthState.Error(error)
            Result.Error(error)
        }
    }

    suspend fun loginWithCredentials(serverUrl: String, username: String, password: String): Result<UserSession> {
        _authState.value = AuthState.LoggingIn

        // Save server URL and update the interceptor cache
        settingsRepository.updateServerUrl(serverUrl)
        dynamicBaseUrlInterceptor.updateServerUrl(serverUrl)

        return try {
            val request = com.gatekey.client.data.model.LocalLoginRequest(username, password)
            val response = api.localLogin(request)

            if (response.isSuccessful) {
                val body = response.body()
                val token = body?.token ?: body?.accessToken

                if (token != null) {
                    // Parse expiration (default to 24 hours from now)
                    val expiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000)

                    // Get user info
                    tokenRepository.saveToken(
                        accessToken = token,
                        expiresAt = expiresAt,
                        userEmail = body?.user?.email ?: username,
                        userName = body?.user?.name ?: username,
                        serverUrl = serverUrl
                    )

                    val user = body?.user ?: com.gatekey.client.data.model.UserSession(
                        id = "",
                        email = username,
                        name = username,
                        groups = emptyList(),
                        isAdmin = false,
                        expiresAt = ""
                    )

                    _currentUser.value = user
                    _authState.value = AuthState.LoggedIn(user)
                    Log.d(TAG, "Local login successful for user: ${user.email}")
                    Result.Success(user)
                } else if (body?.error != null) {
                    _authState.value = AuthState.Error(body.error)
                    Result.Error(body.error)
                } else {
                    _authState.value = AuthState.Error("Login failed - no token received")
                    Result.Error("Login failed - no token received")
                }
            } else {
                val errorBody = response.errorBody()?.string()
                val error = errorBody ?: "Login failed (${response.code()})"
                Log.e(TAG, "Local login failed: $error")
                _authState.value = AuthState.Error(error)
                Result.Error(error, response.code())
            }
        } catch (e: Exception) {
            val error = e.message ?: "Network error"
            Log.e(TAG, "Local login exception: $error", e)
            _authState.value = AuthState.Error(error)
            Result.Error(error)
        }
    }

    suspend fun logout() {
        try {
            api.logout()
        } catch (e: Exception) {
            // Ignore logout errors
        }
        tokenRepository.clearToken()
        _currentUser.value = null
        _authState.value = AuthState.LoggedOut
    }
}
