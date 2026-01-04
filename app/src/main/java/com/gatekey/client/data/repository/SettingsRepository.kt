package com.gatekey.client.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gatekey.client.data.model.AppSettings
import com.gatekey.client.data.model.LogLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "gatekey_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val AUTO_CONNECT_GATEWAY_ID = stringPreferencesKey("auto_connect_gateway_id")
        val SHOW_NOTIFICATIONS = booleanPreferencesKey("show_notifications")
        val KEEP_ALIVE = booleanPreferencesKey("keep_alive")
        val LOG_LEVEL = stringPreferencesKey("log_level")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            serverUrl = prefs[Keys.SERVER_URL] ?: "",
            autoConnect = prefs[Keys.AUTO_CONNECT] ?: false,
            autoConnectGatewayId = prefs[Keys.AUTO_CONNECT_GATEWAY_ID],
            showNotifications = prefs[Keys.SHOW_NOTIFICATIONS] ?: true,
            keepAlive = prefs[Keys.KEEP_ALIVE] ?: true,
            logLevel = try {
                LogLevel.valueOf(prefs[Keys.LOG_LEVEL] ?: "INFO")
            } catch (e: Exception) {
                LogLevel.INFO
            }
        )
    }

    suspend fun updateServerUrl(url: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SERVER_URL] = url.trimEnd('/')
        }
    }

    suspend fun updateAutoConnect(enabled: Boolean, gatewayId: String? = null) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.AUTO_CONNECT] = enabled
            if (gatewayId != null) {
                prefs[Keys.AUTO_CONNECT_GATEWAY_ID] = gatewayId
            } else {
                prefs.remove(Keys.AUTO_CONNECT_GATEWAY_ID)
            }
        }
    }

    suspend fun updateShowNotifications(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SHOW_NOTIFICATIONS] = enabled
        }
    }

    suspend fun updateKeepAlive(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.KEEP_ALIVE] = enabled
        }
    }

    suspend fun updateLogLevel(level: LogLevel) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.LOG_LEVEL] = level.name
        }
    }

    suspend fun clearSettings() {
        context.settingsDataStore.edit { it.clear() }
    }
}
