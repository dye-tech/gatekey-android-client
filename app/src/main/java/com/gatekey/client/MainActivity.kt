package com.gatekey.client

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.gatekey.client.data.repository.SettingsRepository
import com.gatekey.client.ui.GatekeyApp
import com.gatekey.client.ui.theme.GatekeyTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    // Track if we just opened the browser for SSO
    private var ssoInProgress = false
    // Track if we received a callback
    private var callbackReceived = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by settingsRepository.settings.collectAsState(initial = null)
            val darkMode = settings?.darkMode ?: false

            GatekeyTheme(darkTheme = darkMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GatekeyApp()
                }
            }
        }

        // Handle OAuth callback on initial launch
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // If we were waiting for SSO and came back without a callback,
        // notify that SSO was cancelled (user dismissed browser)
        if (ssoInProgress && !callbackReceived) {
            android.util.Log.d("MainActivity", "Returned from SSO without callback - user cancelled")
            OAuthCallbackHandler.handleSsoCancelled()
        }
        // Reset flags
        ssoInProgress = false
        callbackReceived = false
    }

    override fun onPause() {
        super.onPause()
        // Check if we're pausing because SSO browser was opened
        if (SsoStateManager.isWaitingForSso) {
            ssoInProgress = true
            SsoStateManager.isWaitingForSso = false
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent?.data?.let { uri ->
            if (uri.scheme == "gatekey" && uri.host == "callback") {
                // Mark that we received a callback
                callbackReceived = true

                // GateKey server sends token directly in callback URL
                val token = uri.getQueryParameter("token")
                val email = uri.getQueryParameter("email")
                val name = uri.getQueryParameter("name")
                val expiresIn = uri.getQueryParameter("expires_in")
                val error = uri.getQueryParameter("error")

                android.util.Log.d("MainActivity", "SSO callback received - token: ${token?.take(10)}..., email: $email, error: $error, uri: $uri")

                // Process callback if we have a token or an error
                if (token != null) {
                    OAuthCallbackHandler.handleTokenCallback(token, email, name, expiresIn)
                } else if (error != null) {
                    OAuthCallbackHandler.handleErrorCallback(error)
                } else {
                    android.util.Log.d("MainActivity", "Ignoring empty callback - no token or error")
                }
            }
        }
    }
}

/**
 * Callback handler for SSO authentication flow
 */
object OAuthCallbackHandler {
    private var tokenCallback: ((token: String, email: String?, name: String?, expiresIn: String?) -> Unit)? = null
    private var errorCallback: ((error: String) -> Unit)? = null
    private var cancelCallback: (() -> Unit)? = null

    fun setTokenCallback(cb: (token: String, email: String?, name: String?, expiresIn: String?) -> Unit) {
        tokenCallback = cb
    }

    fun setErrorCallback(cb: (error: String) -> Unit) {
        errorCallback = cb
    }

    fun setCancelCallback(cb: () -> Unit) {
        cancelCallback = cb
    }

    fun clearCallbacks() {
        tokenCallback = null
        errorCallback = null
        cancelCallback = null
    }

    fun handleTokenCallback(token: String, email: String?, name: String?, expiresIn: String?) {
        tokenCallback?.invoke(token, email, name, expiresIn)
    }

    fun handleErrorCallback(error: String) {
        errorCallback?.invoke(error)
    }

    fun handleSsoCancelled() {
        cancelCallback?.invoke()
    }
}

/**
 * Manager to track SSO browser state across activity lifecycle
 */
object SsoStateManager {
    @Volatile
    var isWaitingForSso: Boolean = false
}
