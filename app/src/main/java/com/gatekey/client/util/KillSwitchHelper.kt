package com.gatekey.client.util

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Helper for managing VPN kill switch functionality.
 *
 * Android implements kill switch at the system level through "Always-on VPN" with
 * "Block connections without VPN" option. This helper provides utilities to:
 * - Detect if always-on VPN is enabled
 * - Guide users to enable the kill switch
 * - Check network connectivity status
 *
 * Note: The actual blocking is enforced by Android OS when the user enables
 * "Block connections without VPN" in system VPN settings.
 */
object KillSwitchHelper {

    private const val TAG = "KillSwitchHelper"

    /**
     * Check if always-on VPN is enabled for any app on this device.
     * Note: This doesn't tell us if OUR app is the always-on VPN, just that some app is.
     *
     * @return true if always-on VPN appears to be active
     */
    fun isAlwaysOnVpnEnabled(context: Context): Boolean {
        return try {
            // Check if there's an always-on VPN package set
            // This is a best-effort check since Android doesn't expose this directly
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNetwork = connectivityManager.activeNetwork
                val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

                // If VPN transport is active, there's likely an always-on VPN
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check always-on VPN status", e)
            false
        }
    }

    /**
     * Check if the device appears to be blocking non-VPN traffic.
     * This is a heuristic check - not 100% reliable.
     *
     * @return true if non-VPN traffic appears to be blocked
     */
    fun isBlockingNonVpnTraffic(context: Context): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNetwork = connectivityManager.activeNetwork
                if (activeNetwork == null) {
                    // No active network could mean blocking is active
                    return true
                }

                val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                if (capabilities == null) {
                    return true
                }

                // If VPN transport is NOT available but we expect it, blocking might be active
                // This is imperfect since there could be other reasons for no VPN
                false
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check blocking status", e)
            false
        }
    }

    /**
     * Get an intent to open VPN settings where user can enable always-on VPN.
     *
     * @return Intent to open VPN settings
     */
    fun getVpnSettingsIntent(): Intent {
        return Intent(Settings.ACTION_VPN_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * Get an intent to open the specific app's VPN settings (Android 8.0+).
     * This is where users can enable "Always-on VPN" and "Block connections without VPN".
     *
     * @return Intent to open app-specific VPN settings, or general VPN settings on older devices
     */
    fun getAppVpnSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // On Android 8.0+, we can try to open app-specific VPN settings
            // However, there's no direct intent for this, so we use general VPN settings
            Intent(Settings.ACTION_VPN_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            getVpnSettingsIntent()
        }
    }

    /**
     * Instructions for users to enable kill switch.
     *
     * @return Human-readable instructions for enabling kill switch
     */
    fun getKillSwitchInstructions(): String {
        return """
            To enable Kill Switch (block all traffic when VPN disconnects):

            1. Open Android Settings
            2. Go to Network & Internet > VPN
            3. Tap the gear icon next to GateKey
            4. Enable "Always-on VPN"
            5. Enable "Block connections without VPN"

            This ensures no traffic leaks if the VPN disconnects unexpectedly.
        """.trimIndent()
    }

    /**
     * Check if the device supports always-on VPN feature.
     *
     * @return true if always-on VPN is supported
     */
    fun isAlwaysOnVpnSupported(): Boolean {
        // Always-on VPN was introduced in Android 7.0 (API 24)
        // Block connections without VPN was added in Android 8.0 (API 26)
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }

    /**
     * Check if the device supports blocking connections without VPN.
     *
     * @return true if blocking is supported
     */
    fun isBlockingSupported(): Boolean {
        // Block connections without VPN was added in Android 8.0 (API 26)
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }

    /**
     * Log current VPN/kill switch status for debugging.
     */
    fun logStatus(context: Context) {
        Log.d(TAG, "Kill Switch Status:")
        Log.d(TAG, "  Always-on VPN supported: ${isAlwaysOnVpnSupported()}")
        Log.d(TAG, "  Blocking supported: ${isBlockingSupported()}")
        Log.d(TAG, "  Always-on VPN enabled: ${isAlwaysOnVpnEnabled(context)}")
        Log.d(TAG, "  Blocking active: ${isBlockingNonVpnTraffic(context)}")
    }
}
