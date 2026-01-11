package com.gatekey.client.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatekey.client.OAuthCallbackHandler
import com.gatekey.client.data.model.AuthProvider
import com.gatekey.client.data.model.UserSession
import com.gatekey.client.data.repository.AuthRepository
import com.gatekey.client.data.repository.CertificateTrustRepository
import com.gatekey.client.data.repository.Result
import com.gatekey.client.security.CertificatePinChangedException
import com.gatekey.client.security.CertificateRequiresTrustException
import com.gatekey.client.security.TofuTrustManager
import com.gatekey.client.util.UrlValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.net.ssl.SSLHandshakeException

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val certificateTrustRepository: CertificateTrustRepository,
    private val tofuTrustManager: TofuTrustManager
) : ViewModel() {

    val authState = authRepository.authState
    val currentUser = authRepository.currentUser
    val providers = authRepository.providers

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Stores the SSO login URL for manual opening
    private val _ssoLoginUrl = MutableStateFlow<String?>(null)
    val ssoLoginUrl: StateFlow<String?> = _ssoLoginUrl.asStateFlow()

    // TOFU (Trust on First Use) certificate trust prompt state
    private val _trustPrompt = MutableStateFlow<TrustPromptState?>(null)
    val trustPrompt: StateFlow<TrustPromptState?> = _trustPrompt.asStateFlow()

    // Pending action to retry after trust decision
    private var pendingTrustAction: (() -> Unit)? = null

    init {
        android.util.Log.d("AuthViewModel", "init block - setting up OAuth callbacks (this=$this)")
        // Use setCallbacks with owner tracking to prevent race conditions
        // when old ViewModels are cleared after new ones are created
        OAuthCallbackHandler.setCallbacks(
            owner = this,
            onToken = { token, email, name, expiresIn ->
                android.util.Log.d("AuthViewModel", "Token callback lambda invoked - launching coroutine")
                viewModelScope.launch {
                    handleTokenCallback(token, email, name, expiresIn)
                }
            },
            onError = { error ->
                viewModelScope.launch {
                    handleErrorCallback(error)
                }
            },
            onCancel = {
                viewModelScope.launch {
                    handleSsoCancelled()
                }
            }
        )
    }

    override fun onCleared() {
        android.util.Log.d("AuthViewModel", "onCleared called - clearing callbacks (this=$this)")
        super.onCleared()
        // Only clear callbacks if this ViewModel is the current owner
        // This prevents old ViewModels from clearing callbacks set by newer ones
        OAuthCallbackHandler.clearCallbacksIfOwner(this)
    }

    private fun handleSsoCancelled() {
        // User returned from browser without completing SSO - clear loading state
        if (_isLoading.value) {
            android.util.Log.d("AuthViewModel", "SSO cancelled by user, clearing loading state")
            _isLoading.value = false
            // Reset auth state if it was in LoggingIn
            if (authState.value is AuthRepository.AuthState.LoggingIn) {
                authRepository.resetAuthState()
            }
        }
    }

    fun checkAuthState() {
        viewModelScope.launch {
            authRepository.checkAuthState()
        }
    }

    fun updateServerUrl(url: String) {
        _serverUrl.value = url
    }

    fun fetchProviders() {
        fetchProvidersInternal()
    }

    private fun fetchProvidersInternal() {
        viewModelScope.launch {
            val url = _serverUrl.value.trim()
            // Don't try to fetch providers if URL is empty or looks incomplete
            // A valid URL should have at least a domain with a dot (e.g., "example.com")
            if (url.isBlank() || !url.contains(".")) {
                return@launch
            }

            _isLoading.value = true
            // Don't show errors for provider fetch - just silently fail
            // Errors will be shown when user actually tries to login
            try {
                when (val result = authRepository.fetchProvidersForUrl(url)) {
                    is Result.Success -> {
                        // Providers fetched
                    }
                    is Result.Error -> {
                        // Check if it's a TOFU exception that needs user trust decision
                        val exception = result.exception
                        if (exception != null && handleSslException(exception) { fetchProvidersInternal() }) {
                            // Trust prompt shown, waiting for user decision
                            return@launch
                        }
                        // Silently ignore other errors - don't show error while typing
                        android.util.Log.d("AuthViewModel", "Provider fetch failed: ${result.message}")
                    }
                    is Result.Loading -> {}
                }
            } catch (e: Exception) {
                // Handle TOFU exceptions that weren't caught in Result
                if (handleSslException(e) { fetchProvidersInternal() }) {
                    return@launch
                }
                android.util.Log.d("AuthViewModel", "Provider fetch exception: ${e.message}")
            }
            _isLoading.value = false
        }
    }

    fun initiateLogin() {
        initiateLoginWithProvider(null)
    }

    fun initiateLoginWithProvider(provider: AuthProvider?) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _ssoLoginUrl.value = null

            val url = _serverUrl.value.trim()

            // Validate URL format and security
            val validationResult = UrlValidator.validate(url)
            val serverUrl = when (validationResult) {
                is UrlValidator.ValidationResult.Valid -> validationResult.normalizedUrl
                is UrlValidator.ValidationResult.Invalid -> {
                    _error.value = validationResult.error
                    _isLoading.value = false
                    return@launch
                }
            }

            when (val result = authRepository.initiateLogin(serverUrl, provider)) {
                is Result.Success -> {
                    // Store the login URL so user can click to open
                    _ssoLoginUrl.value = result.data
                    // Keep loading state while waiting for user to click
                    // It will be cleared when callback is received or on error
                }
                is Result.Error -> {
                    _error.value = result.message
                    _isLoading.value = false
                }
                is Result.Loading -> {}
            }
            // Note: Don't set isLoading = false here for SSO
            // The loading state should remain until callback is processed
        }
    }

    fun openSsoInBrowser() {
        _ssoLoginUrl.value?.let { url ->
            authRepository.openLoginInBrowser(url)
        }
    }

    fun cancelSsoLogin() {
        _ssoLoginUrl.value = null
        _isLoading.value = false
        authRepository.resetAuthState()
    }

    fun loginWithApiKey(apiKey: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            val url = _serverUrl.value.trim()

            // Validate URL format and security
            val validationResult = UrlValidator.validate(url)
            val serverUrl = when (validationResult) {
                is UrlValidator.ValidationResult.Valid -> validationResult.normalizedUrl
                is UrlValidator.ValidationResult.Invalid -> {
                    _error.value = validationResult.error
                    _isLoading.value = false
                    return@launch
                }
            }

            when (val result = authRepository.loginWithApiKey(serverUrl, apiKey)) {
                is Result.Success -> {
                    // Login successful
                }
                is Result.Error -> {
                    _error.value = result.message
                }
                is Result.Loading -> {}
            }
            _isLoading.value = false
        }
    }

    fun loginWithCredentials(username: String, password: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            val url = _serverUrl.value.trim()

            // Validate URL format and security
            val validationResult = UrlValidator.validate(url)
            val serverUrl = when (validationResult) {
                is UrlValidator.ValidationResult.Valid -> validationResult.normalizedUrl
                is UrlValidator.ValidationResult.Invalid -> {
                    _error.value = validationResult.error
                    _isLoading.value = false
                    return@launch
                }
            }

            if (username.isEmpty() || password.isEmpty()) {
                _error.value = "Please enter username and password"
                _isLoading.value = false
                return@launch
            }

            when (val result = authRepository.loginWithCredentials(serverUrl, username, password)) {
                is Result.Success -> {
                    // Login successful
                }
                is Result.Error -> {
                    _error.value = result.message
                }
                is Result.Loading -> {}
            }
            _isLoading.value = false
        }
    }

    private suspend fun handleTokenCallback(token: String, email: String?, name: String?, expiresIn: String?) {
        android.util.Log.d("AuthViewModel", "handleTokenCallback STARTING - email: $email, token: ${token.take(10)}...")
        _ssoLoginUrl.value = null // Clear the login URL on success
        val result = authRepository.handleDirectToken(token, email, name, expiresIn)
        android.util.Log.d("AuthViewModel", "handleTokenCallback result: $result, authState now: ${authRepository.authState.value}")
        _isLoading.value = false

        if (result is Result.Error) {
            android.util.Log.e("AuthViewModel", "handleTokenCallback ERROR: ${result.message}")
            _error.value = result.message
        } else {
            android.util.Log.d("AuthViewModel", "handleTokenCallback SUCCESS - should navigate to Home")
        }
    }

    private fun handleErrorCallback(error: String) {
        android.util.Log.e("AuthViewModel", "SSO error: $error")
        _ssoLoginUrl.value = null // Clear the login URL on error
        _isLoading.value = false
        _error.value = error
        authRepository.resetAuthState()
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Handle trust decision from user.
     * If user trusts, save the pin and retry the pending action.
     */
    fun handleTrustDecision(trust: Boolean) {
        val prompt = _trustPrompt.value ?: return

        viewModelScope.launch {
            if (trust) {
                // Save the trusted pin
                if (prompt.isChanged) {
                    certificateTrustRepository.updateServerPin(prompt.hostname, prompt.pin)
                } else {
                    certificateTrustRepository.trustServer(prompt.hostname, prompt.pin)
                }

                // Add session trust for immediate retry
                tofuTrustManager.addSessionTrust(prompt.hostname, prompt.pin)

                android.util.Log.i("AuthViewModel", "User trusted server: ${prompt.hostname}")

                // Clear the prompt
                _trustPrompt.value = null

                // Retry the pending action
                pendingTrustAction?.invoke()
                pendingTrustAction = null
            } else {
                android.util.Log.i("AuthViewModel", "User rejected server: ${prompt.hostname}")
                _trustPrompt.value = null
                pendingTrustAction = null
                _isLoading.value = false
                _error.value = "Connection cancelled - server not trusted"
            }
        }
    }

    /**
     * Handle SSL exceptions, showing trust prompts for TOFU exceptions.
     * Returns true if the exception was handled (trust prompt shown).
     */
    private fun handleSslException(e: Throwable, retryAction: () -> Unit): Boolean {
        // Find the TOFU exception in the cause chain
        var cause: Throwable? = e
        while (cause != null) {
            when (cause) {
                is CertificateRequiresTrustException -> {
                    android.util.Log.i("AuthViewModel", "First connection to ${cause.hostname}, prompting for trust")
                    _trustPrompt.value = TrustPromptState(
                        hostname = cause.hostname,
                        pin = cause.pin,
                        oldPin = null,
                        isChanged = false
                    )
                    pendingTrustAction = retryAction
                    return true
                }
                is CertificatePinChangedException -> {
                    android.util.Log.w("AuthViewModel", "Certificate changed for ${cause.hostname}!")
                    _trustPrompt.value = TrustPromptState(
                        hostname = cause.hostname,
                        pin = cause.newPin,
                        oldPin = cause.oldPin,
                        isChanged = true
                    )
                    pendingTrustAction = retryAction
                    return true
                }
            }
            cause = cause.cause
        }
        return false
    }

    fun dismissTrustPrompt() {
        _trustPrompt.value = null
        pendingTrustAction = null
        _isLoading.value = false
    }
}

/**
 * State for certificate trust prompt UI.
 */
data class TrustPromptState(
    val hostname: String,
    val pin: String,
    val oldPin: String?, // Non-null if certificate changed
    val isChanged: Boolean
)
