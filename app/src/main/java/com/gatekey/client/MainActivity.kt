package com.gatekey.client

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import com.gatekey.client.ui.viewmodel.ConnectionViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

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
        android.util.Log.d("MainActivity", "onNewIntent called - data: ${intent.data}")
        setIntent(intent) // Update the activity's intent to the new one
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.d("MainActivity", "onResume - ssoInProgress: $ssoInProgress, callbackReceived: $callbackReceived")
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

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult - requestCode: $requestCode, resultCode: $resultCode")

        if (requestCode == ConnectionViewModel.VPN_PERMISSION_REQUEST_CODE) {
            val granted = resultCode == Activity.RESULT_OK
            Log.d(TAG, "VPN permission result - granted: $granted")
            VpnPermissionHandler.handlePermissionResult(granted, this)
        }
    }

    private fun handleIntent(intent: Intent?) {
        android.util.Log.d("MainActivity", "handleIntent called - intent data: ${intent?.data}")
        intent?.data?.let { uri ->
            android.util.Log.d("MainActivity", "URI received - scheme: ${uri.scheme}, host: ${uri.host}")
            if (uri.scheme == "gatekey" && uri.host == "callback") {
                // Mark that we received a callback
                callbackReceived = true
                android.util.Log.d("MainActivity", "Valid gatekey callback - callbackReceived set to true")

                // GateKey server sends token directly in callback URL
                val token = uri.getQueryParameter("token")
                val email = uri.getQueryParameter("email")
                val name = uri.getQueryParameter("name")
                val expiresIn = uri.getQueryParameter("expires_in")
                val error = uri.getQueryParameter("error")

                android.util.Log.d("MainActivity", "SSO callback params - token: ${token?.take(10)}..., email: $email, error: $error")

                // Process callback if we have a token or an error
                if (token != null) {
                    android.util.Log.d("MainActivity", "Calling OAuthCallbackHandler.handleTokenCallback with token")
                    OAuthCallbackHandler.handleTokenCallback(token, email, name, expiresIn)
                    android.util.Log.d("MainActivity", "handleTokenCallback completed")
                } else if (error != null) {
                    OAuthCallbackHandler.handleErrorCallback(error)
                } else {
                    android.util.Log.d("MainActivity", "Ignoring empty callback - no token or error")
                }
            } else {
                android.util.Log.d("MainActivity", "Ignoring non-gatekey callback URI")
            }
        } ?: run {
            android.util.Log.d("MainActivity", "handleIntent called with null intent or no data")
        }
    }
}

/**
 * Callback handler for SSO authentication flow
 * Tracks callback owner to prevent race conditions when ViewModels are cleared
 */
object OAuthCallbackHandler {
    private const val TAG = "OAuthCallbackHandler"
    private var tokenCallback: ((token: String, email: String?, name: String?, expiresIn: String?) -> Unit)? = null
    private var errorCallback: ((error: String) -> Unit)? = null
    private var cancelCallback: (() -> Unit)? = null

    // Track which ViewModel instance owns the callbacks to prevent race conditions
    private var callbackOwner: Any? = null

    fun setCallbacks(
        owner: Any,
        onToken: (token: String, email: String?, name: String?, expiresIn: String?) -> Unit,
        onError: (error: String) -> Unit,
        onCancel: () -> Unit
    ) {
        android.util.Log.d(TAG, "setCallbacks called - owner: $owner")
        callbackOwner = owner
        tokenCallback = onToken
        errorCallback = onError
        cancelCallback = onCancel
    }

    fun setTokenCallback(cb: (token: String, email: String?, name: String?, expiresIn: String?) -> Unit) {
        android.util.Log.d(TAG, "setTokenCallback called - callback is now set")
        tokenCallback = cb
    }

    fun setErrorCallback(cb: (error: String) -> Unit) {
        errorCallback = cb
    }

    fun setCancelCallback(cb: () -> Unit) {
        cancelCallback = cb
    }

    /**
     * Only clear callbacks if the caller is the current owner.
     * This prevents old ViewModels from clearing callbacks set by newer ViewModels.
     */
    fun clearCallbacksIfOwner(owner: Any) {
        if (callbackOwner === owner) {
            android.util.Log.d(TAG, "clearCallbacksIfOwner - owner matches, clearing callbacks")
            tokenCallback = null
            errorCallback = null
            cancelCallback = null
            callbackOwner = null
        } else {
            android.util.Log.d(TAG, "clearCallbacksIfOwner - owner mismatch, NOT clearing (current: $callbackOwner, caller: $owner)")
        }
    }

    fun clearCallbacks() {
        android.util.Log.d(TAG, "clearCallbacks called - all callbacks cleared")
        tokenCallback = null
        errorCallback = null
        cancelCallback = null
        callbackOwner = null
    }

    fun handleTokenCallback(token: String, email: String?, name: String?, expiresIn: String?) {
        android.util.Log.d(TAG, "handleTokenCallback called - tokenCallback is ${if (tokenCallback != null) "SET" else "NULL"}")
        if (tokenCallback == null) {
            android.util.Log.e(TAG, "ERROR: tokenCallback is NULL, cannot process token!")
        }
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

/**
 * Callback handler for VPN permission flow
 * Bridges the gap between Activity's onActivityResult and ViewModel's permission handling
 */
object VpnPermissionHandler {
    private const val TAG = "VpnPermissionHandler"
    private var permissionCallback: ((granted: Boolean, activity: Activity) -> Unit)? = null

    fun setCallback(callback: (granted: Boolean, activity: Activity) -> Unit) {
        android.util.Log.d(TAG, "setCallback - callback registered")
        permissionCallback = callback
    }

    fun clearCallback() {
        android.util.Log.d(TAG, "clearCallback - callback cleared")
        permissionCallback = null
    }

    fun handlePermissionResult(granted: Boolean, activity: Activity) {
        android.util.Log.d(TAG, "handlePermissionResult - granted: $granted, callback is ${if (permissionCallback != null) "SET" else "NULL"}")
        permissionCallback?.invoke(granted, activity)
    }
}
