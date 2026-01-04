package com.gatekey.client.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatekey.client.data.model.AppSettings
import com.gatekey.client.data.model.LogLevel
import com.gatekey.client.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )

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
        }
    }
}
