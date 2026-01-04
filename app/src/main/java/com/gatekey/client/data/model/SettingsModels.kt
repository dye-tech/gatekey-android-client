package com.gatekey.client.data.model

/**
 * Application settings
 */
data class AppSettings(
    val serverUrl: String = "",
    val autoConnect: Boolean = false,
    val autoConnectGatewayId: String? = null,
    val showNotifications: Boolean = true,
    val keepAlive: Boolean = true,
    val logLevel: LogLevel = LogLevel.INFO,
    val darkMode: Boolean = false
)

enum class LogLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR
}

/**
 * Server configuration
 */
data class ServerConfig(
    val url: String,
    val name: String? = null,
    val isDefault: Boolean = false
)
