package com.gatekey.client.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gatekey.client.data.model.StoredToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tokenDataStore: DataStore<Preferences> by preferencesDataStore(name = "gatekey_token")

@Singleton
class TokenRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val EXPIRES_AT = longPreferencesKey("expires_at")
        val USER_EMAIL = stringPreferencesKey("user_email")
        val USER_NAME = stringPreferencesKey("user_name")
        val SERVER_URL = stringPreferencesKey("server_url")
    }

    val storedToken: Flow<StoredToken?> = context.tokenDataStore.data.map { prefs ->
        val token = prefs[Keys.ACCESS_TOKEN]
        if (token != null) {
            StoredToken(
                accessToken = token,
                expiresAt = prefs[Keys.EXPIRES_AT] ?: 0L,
                userEmail = prefs[Keys.USER_EMAIL] ?: "",
                userName = prefs[Keys.USER_NAME] ?: "",
                serverUrl = prefs[Keys.SERVER_URL] ?: ""
            )
        } else {
            null
        }
    }

    suspend fun saveToken(
        accessToken: String,
        expiresAt: Long,
        userEmail: String,
        userName: String,
        serverUrl: String
    ) {
        context.tokenDataStore.edit { prefs ->
            prefs[Keys.ACCESS_TOKEN] = accessToken
            prefs[Keys.EXPIRES_AT] = expiresAt
            prefs[Keys.USER_EMAIL] = userEmail
            prefs[Keys.USER_NAME] = userName
            prefs[Keys.SERVER_URL] = serverUrl
        }
    }

    suspend fun getToken(): String? {
        return context.tokenDataStore.data.first()[Keys.ACCESS_TOKEN]
    }

    suspend fun clearToken() {
        context.tokenDataStore.edit { prefs ->
            prefs.remove(Keys.ACCESS_TOKEN)
            prefs.remove(Keys.EXPIRES_AT)
            prefs.remove(Keys.USER_EMAIL)
            prefs.remove(Keys.USER_NAME)
        }
    }

    fun isTokenExpired(): Boolean {
        return try {
            val expiresAt = kotlinx.coroutines.runBlocking {
                context.tokenDataStore.data.first()[Keys.EXPIRES_AT] ?: 0L
            }
            System.currentTimeMillis() >= expiresAt
        } catch (e: Exception) {
            true
        }
    }

    suspend fun hasValidToken(): Boolean {
        val token = getToken()
        return token != null && !isTokenExpired()
    }
}
