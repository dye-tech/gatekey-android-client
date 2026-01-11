package com.gatekey.client.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.gatekey.client.data.model.StoredToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TokenRepository"
private const val PREFS_FILE_NAME = "gatekey_secure_token"

@Singleton
class TokenRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        const val ACCESS_TOKEN = "access_token"
        const val EXPIRES_AT = "expires_at"
        const val USER_EMAIL = "user_email"
        const val USER_NAME = "user_name"
        const val SERVER_URL = "server_url"
    }

    // Get or create the master key alias using hardware-backed KeyStore when available
    // MasterKeys.AES256_GCM_SPEC uses AES256-GCM encryption with hardware KeyStore
    private val masterKeyAlias: String by lazy {
        try {
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create master key", e)
            throw SecurityException("Cannot create encryption key", e)
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
            throw SecurityException("Cannot create secure storage for tokens", e)
        }
    }

    // StateFlow to emit token changes
    private val _storedTokenFlow = MutableStateFlow<StoredToken?>(null)

    init {
        // Initialize the flow with current stored token
        refreshStoredToken()
    }

    private fun refreshStoredToken() {
        val token = encryptedPrefs.getString(Keys.ACCESS_TOKEN, null)
        _storedTokenFlow.value = if (token != null) {
            StoredToken(
                accessToken = token,
                expiresAt = encryptedPrefs.getLong(Keys.EXPIRES_AT, 0L),
                userEmail = encryptedPrefs.getString(Keys.USER_EMAIL, "") ?: "",
                userName = encryptedPrefs.getString(Keys.USER_NAME, "") ?: "",
                serverUrl = encryptedPrefs.getString(Keys.SERVER_URL, "") ?: ""
            )
        } else {
            null
        }
    }

    val storedToken: Flow<StoredToken?> = _storedTokenFlow

    suspend fun saveToken(
        accessToken: String,
        expiresAt: Long,
        userEmail: String,
        userName: String,
        serverUrl: String
    ) = withContext(Dispatchers.IO) {
        encryptedPrefs.edit()
            .putString(Keys.ACCESS_TOKEN, accessToken)
            .putLong(Keys.EXPIRES_AT, expiresAt)
            .putString(Keys.USER_EMAIL, userEmail)
            .putString(Keys.USER_NAME, userName)
            .putString(Keys.SERVER_URL, serverUrl)
            .apply()

        refreshStoredToken()
        Log.d(TAG, "Token saved securely for user: $userEmail")
    }

    suspend fun getToken(): String? = withContext(Dispatchers.IO) {
        encryptedPrefs.getString(Keys.ACCESS_TOKEN, null)
    }

    suspend fun getExpiresAt(): Long = withContext(Dispatchers.IO) {
        encryptedPrefs.getLong(Keys.EXPIRES_AT, 0L)
    }

    suspend fun clearToken() = withContext(Dispatchers.IO) {
        encryptedPrefs.edit()
            .remove(Keys.ACCESS_TOKEN)
            .remove(Keys.EXPIRES_AT)
            .remove(Keys.USER_EMAIL)
            .remove(Keys.USER_NAME)
            // Keep SERVER_URL for convenience on re-login
            .apply()

        refreshStoredToken()
        Log.d(TAG, "Token cleared from secure storage")
    }

    fun isTokenExpired(): Boolean {
        val expiresAt = encryptedPrefs.getLong(Keys.EXPIRES_AT, 0L)
        return System.currentTimeMillis() >= expiresAt
    }

    /**
     * Check if token will expire within the given buffer time (in milliseconds).
     * Useful for proactive token refresh.
     */
    fun isTokenExpiringSoon(bufferMs: Long = 5 * 60 * 1000): Boolean {
        val expiresAt = encryptedPrefs.getLong(Keys.EXPIRES_AT, 0L)
        return System.currentTimeMillis() + bufferMs >= expiresAt
    }

    suspend fun hasValidToken(): Boolean {
        val token = getToken()
        return token != null && !isTokenExpired()
    }
}
