package com.gatekey.client.util

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.gatekey.client.data.model.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Application-wide logger that respects log level settings and writes to file.
 * Logs can be exported/shared for debugging.
 *
 * Security: Automatically sanitizes sensitive data like tokens, passwords, and API keys.
 */
object AppLogger {
    private const val TAG = "GateKey"
    private const val LOG_FILE_NAME = "gatekey.log"
    private const val MAX_LOG_SIZE_BYTES = 5 * 1024 * 1024 // 5MB max
    private const val MAX_MEMORY_LOGS = 1000

    private var currentLogLevel: LogLevel = LogLevel.INFO
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // Patterns for sensitive data that should be redacted
    private val sensitivePatterns = listOf(
        // Bearer tokens
        Regex("Bearer\\s+[A-Za-z0-9_.-]+", RegexOption.IGNORE_CASE) to "Bearer [REDACTED]",
        // API keys with gk_ prefix
        Regex("gk_[A-Za-z0-9+/=]+", RegexOption.IGNORE_CASE) to "[API_KEY_REDACTED]",
        // Generic token patterns
        Regex("token[\"']?\\s*[:=]\\s*[\"']?[A-Za-z0-9_.-]{20,}[\"']?", RegexOption.IGNORE_CASE) to "token=[REDACTED]",
        // Password patterns
        Regex("password[\"']?\\s*[:=]\\s*[\"']?[^\"'\\s]+[\"']?", RegexOption.IGNORE_CASE) to "password=[REDACTED]",
        // Authorization headers
        Regex("Authorization[\"']?\\s*[:=]\\s*[\"']?[^\"'\\n]+[\"']?", RegexOption.IGNORE_CASE) to "Authorization=[REDACTED]",
        // Private keys (WireGuard/OpenVPN)
        Regex("PrivateKey\\s*=\\s*[A-Za-z0-9+/=]+", RegexOption.IGNORE_CASE) to "PrivateKey=[REDACTED]",
        // Session IDs
        Regex("session[_-]?id[\"']?\\s*[:=]\\s*[\"']?[A-Za-z0-9_.-]+[\"']?", RegexOption.IGNORE_CASE) to "session_id=[REDACTED]",
        // Cookies
        Regex("Cookie[\"']?\\s*[:=]\\s*[\"']?[^\"'\\n]+[\"']?", RegexOption.IGNORE_CASE) to "Cookie=[REDACTED]"
    )

    // In-memory log buffer for quick access
    private val logBuffer = ConcurrentLinkedQueue<String>()

    /**
     * Initialize the logger with application context
     */
    fun init(context: Context) {
        logFile = File(context.filesDir, LOG_FILE_NAME)
        // Rotate log if too large
        rotateLogIfNeeded()
        log(LogLevel.INFO, "AppLogger", "Logger initialized")
    }

    /**
     * Update the current log level
     */
    fun setLogLevel(level: LogLevel) {
        currentLogLevel = level
        log(LogLevel.INFO, "AppLogger", "Log level changed to: $level")
    }

    /**
     * Get current log level
     */
    fun getLogLevel(): LogLevel = currentLogLevel

    /**
     * Log a debug message
     */
    fun d(tag: String, message: String) {
        log(LogLevel.DEBUG, tag, message)
    }

    /**
     * Log an info message
     */
    fun i(tag: String, message: String) {
        log(LogLevel.INFO, tag, message)
    }

    /**
     * Log a warning message
     */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        log(LogLevel.WARNING, tag, message, throwable)
    }

    /**
     * Log an error message
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log(LogLevel.ERROR, tag, message, throwable)
    }

    /**
     * Sanitize a message by redacting sensitive information.
     * This prevents tokens, passwords, and other secrets from appearing in logs.
     */
    private fun sanitize(message: String): String {
        var sanitized = message
        for ((pattern, replacement) in sensitivePatterns) {
            sanitized = pattern.replace(sanitized, replacement)
        }
        return sanitized
    }

    private fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        // Check if this log level should be shown
        if (level.ordinal < currentLogLevel.ordinal) {
            return
        }

        // Sanitize the message before logging
        val sanitizedMessage = sanitize(message)

        val timestamp = dateFormat.format(Date())
        val levelChar = when (level) {
            LogLevel.DEBUG -> 'D'
            LogLevel.INFO -> 'I'
            LogLevel.WARNING -> 'W'
            LogLevel.ERROR -> 'E'
        }

        val logLine = "$timestamp $levelChar/$tag: $sanitizedMessage"

        // Sanitize throwable stack trace as well
        val sanitizedStackTrace = throwable?.stackTraceToString()?.let { sanitize(it) }
        val fullLog = if (sanitizedStackTrace != null) {
            "$logLine\n$sanitizedStackTrace"
        } else {
            logLine
        }

        // Log to Android logcat (also sanitized)
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, sanitizedMessage, throwable)
            LogLevel.INFO -> Log.i(tag, sanitizedMessage, throwable)
            LogLevel.WARNING -> Log.w(tag, sanitizedMessage, throwable)
            LogLevel.ERROR -> Log.e(tag, sanitizedMessage, throwable)
        }

        // Add to memory buffer
        addToBuffer(fullLog)

        // Write to file
        writeToFile(fullLog)
    }

    private fun addToBuffer(logLine: String) {
        logBuffer.add(logLine)
        // Trim buffer if too large
        while (logBuffer.size > MAX_MEMORY_LOGS) {
            logBuffer.poll()
        }
    }

    private fun writeToFile(logLine: String) {
        try {
            logFile?.let { file ->
                file.appendText("$logLine\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file", e)
        }
    }

    private fun rotateLogIfNeeded() {
        try {
            logFile?.let { file ->
                if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
                    // Keep last portion of log
                    val content = file.readText()
                    val keepFrom = content.length - (MAX_LOG_SIZE_BYTES / 2).toInt()
                    if (keepFrom > 0) {
                        val newContent = content.substring(keepFrom)
                        file.writeText("--- Log rotated ---\n$newContent")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate log file", e)
        }
    }

    /**
     * Get all logs from memory buffer
     */
    fun getRecentLogs(): List<String> {
        return logBuffer.toList()
    }

    /**
     * Get logs from file
     */
    suspend fun getLogsFromFile(): String = withContext(Dispatchers.IO) {
        try {
            logFile?.readText() ?: "No log file found"
        } catch (e: Exception) {
            "Failed to read log file: ${e.message}"
        }
    }

    /**
     * Clear all logs
     */
    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        logBuffer.clear()
        try {
            logFile?.writeText("")
            log(LogLevel.INFO, "AppLogger", "Logs cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear log file", e)
        }
    }

    /**
     * Get the log file for sharing
     */
    fun getLogFile(): File? = logFile

    /**
     * Create a share intent for the log file
     */
    fun createShareIntent(context: Context): Intent? {
        val file = logFile ?: return null
        if (!file.exists()) return null

        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "GateKey Logs - ${dateFormat.format(Date())}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create share intent", e)
            null
        }
    }

    /**
     * Get log file size in human-readable format
     */
    fun getLogFileSize(): String {
        val file = logFile ?: return "0 B"
        if (!file.exists()) return "0 B"

        val bytes = file.length()
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
}
