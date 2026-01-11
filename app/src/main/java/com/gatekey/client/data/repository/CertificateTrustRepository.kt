package com.gatekey.client.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for storing trusted certificate pins per server (TOFU - Trust on First Use).
 *
 * Stores SHA-256 pins of server certificates in encrypted storage.
 * On first connection to a server, the user is prompted to trust it.
 * On subsequent connections, the pin is verified and user is warned if it changes.
 */
@Singleton
class CertificateTrustRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CertificateTrustRepo"
        private const val PREFS_FILE_NAME = "gatekey_trusted_certs"
        private const val KEY_PREFIX_PIN = "pin_"
        private const val KEY_PREFIX_FIRST_SEEN = "first_seen_"
        private const val KEY_PREFIX_LAST_VERIFIED = "last_verified_"
    }

    private val masterKeyAlias: String by lazy {
        try {
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create master key", e)
            throw SecurityException("Cannot create encryption key for certificate trust store", e)
        }
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            EncryptedSharedPreferences.create(
                PREFS_FILE_NAME,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create EncryptedSharedPreferences", e)
            throw SecurityException("Cannot create secure storage for trusted certificates", e)
        }
    }

    // Flow to notify UI of trusted servers changes
    private val _trustedServers = MutableStateFlow<List<TrustedServer>>(emptyList())
    val trustedServers: StateFlow<List<TrustedServer>> = _trustedServers

    init {
        refreshTrustedServers()
    }

    /**
     * Get the stored pin for a hostname, if any.
     */
    fun getStoredPin(hostname: String): String? {
        val normalizedHost = normalizeHostname(hostname)
        return encryptedPrefs.getString("$KEY_PREFIX_PIN$normalizedHost", null)
    }

    /**
     * Check if we have a stored pin for this hostname.
     */
    fun hasTrustedPin(hostname: String): Boolean {
        return getStoredPin(hostname) != null
    }

    /**
     * Store a trusted pin for a hostname.
     */
    suspend fun trustServer(hostname: String, pin: String) = withContext(Dispatchers.IO) {
        val normalizedHost = normalizeHostname(hostname)
        val now = System.currentTimeMillis()

        encryptedPrefs.edit()
            .putString("$KEY_PREFIX_PIN$normalizedHost", pin)
            .putLong("$KEY_PREFIX_FIRST_SEEN$normalizedHost", now)
            .putLong("$KEY_PREFIX_LAST_VERIFIED$normalizedHost", now)
            .apply()

        Log.i(TAG, "Trusted new server: $normalizedHost")
        refreshTrustedServers()
    }

    /**
     * Update the pin for a hostname (after user confirms trust for changed cert).
     */
    suspend fun updateServerPin(hostname: String, newPin: String) = withContext(Dispatchers.IO) {
        val normalizedHost = normalizeHostname(hostname)
        val now = System.currentTimeMillis()

        encryptedPrefs.edit()
            .putString("$KEY_PREFIX_PIN$normalizedHost", newPin)
            .putLong("$KEY_PREFIX_LAST_VERIFIED$normalizedHost", now)
            .apply()

        Log.i(TAG, "Updated pin for server: $normalizedHost")
        refreshTrustedServers()
    }

    /**
     * Update the last verified timestamp for a hostname.
     */
    suspend fun updateLastVerified(hostname: String) = withContext(Dispatchers.IO) {
        val normalizedHost = normalizeHostname(hostname)
        encryptedPrefs.edit()
            .putLong("$KEY_PREFIX_LAST_VERIFIED$normalizedHost", System.currentTimeMillis())
            .apply()
    }

    /**
     * Remove trust for a hostname.
     */
    suspend fun removeTrust(hostname: String) = withContext(Dispatchers.IO) {
        val normalizedHost = normalizeHostname(hostname)
        encryptedPrefs.edit()
            .remove("$KEY_PREFIX_PIN$normalizedHost")
            .remove("$KEY_PREFIX_FIRST_SEEN$normalizedHost")
            .remove("$KEY_PREFIX_LAST_VERIFIED$normalizedHost")
            .apply()

        Log.i(TAG, "Removed trust for server: $normalizedHost")
        refreshTrustedServers()
    }

    /**
     * Clear all trusted servers.
     */
    suspend fun clearAllTrust() = withContext(Dispatchers.IO) {
        encryptedPrefs.edit().clear().apply()
        Log.i(TAG, "Cleared all trusted servers")
        refreshTrustedServers()
    }

    /**
     * Get all trusted servers.
     */
    fun getAllTrustedServers(): List<TrustedServer> {
        val servers = mutableListOf<TrustedServer>()
        val allKeys = encryptedPrefs.all.keys

        val hostnames = allKeys
            .filter { it.startsWith(KEY_PREFIX_PIN) }
            .map { it.removePrefix(KEY_PREFIX_PIN) }

        for (hostname in hostnames) {
            val pin = encryptedPrefs.getString("$KEY_PREFIX_PIN$hostname", null) ?: continue
            val firstSeen = encryptedPrefs.getLong("$KEY_PREFIX_FIRST_SEEN$hostname", 0L)
            val lastVerified = encryptedPrefs.getLong("$KEY_PREFIX_LAST_VERIFIED$hostname", 0L)

            servers.add(TrustedServer(
                hostname = hostname,
                pinSha256 = pin,
                firstSeenAt = firstSeen,
                lastVerifiedAt = lastVerified
            ))
        }

        return servers.sortedBy { it.hostname }
    }

    private fun refreshTrustedServers() {
        _trustedServers.value = getAllTrustedServers()
    }

    private fun normalizeHostname(hostname: String): String {
        return hostname.lowercase().trim()
    }
}

/**
 * Represents a trusted server with its certificate pin.
 */
data class TrustedServer(
    val hostname: String,
    val pinSha256: String,
    val firstSeenAt: Long,
    val lastVerifiedAt: Long
)
