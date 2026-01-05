package com.gatekey.client.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatekey.client.data.model.AppSettings
import com.gatekey.client.data.model.LogLevel
import com.gatekey.client.data.repository.SettingsRepository
import com.gatekey.client.util.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )

    private val _logFileSize = MutableStateFlow(AppLogger.getLogFileSize())
    val logFileSize: StateFlow<String> = _logFileSize.asStateFlow()

    init {
        // Sync log level with settings on startup
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                AppLogger.setLogLevel(settings.logLevel)
                _logFileSize.value = AppLogger.getLogFileSize()
            }
        }
    }

    fun updateAutoConnect(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateAutoConnect(enabled)
        }
    }

    fun updateShowNotifications(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateShowNotifications(enabled)
        }
    }

    fun updateKeepAlive(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateKeepAlive(enabled)
        }
    }

    fun updateLogLevel(level: LogLevel) {
        viewModelScope.launch {
            settingsRepository.updateLogLevel(level)
            AppLogger.setLogLevel(level)
            AppLogger.i("SettingsViewModel", "Log level updated to: $level")
        }
    }

    /**
     * Create a share intent for exporting logs
     */
    fun getLogShareIntent(): Intent? {
        return AppLogger.createShareIntent(appContext)
    }

    /**
     * Clear all logs
     */
    fun clearLogs() {
        viewModelScope.launch {
            AppLogger.clearLogs()
            _logFileSize.value = AppLogger.getLogFileSize()
        }
    }

    /**
     * Refresh log file size
     */
    fun refreshLogFileSize() {
        _logFileSize.value = AppLogger.getLogFileSize()
    }
}
