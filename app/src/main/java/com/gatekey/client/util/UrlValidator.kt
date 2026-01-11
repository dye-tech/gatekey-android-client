package com.gatekey.client.util

import android.util.Patterns
import java.net.URI
import java.net.URISyntaxException

/**
 * Utility for validating and sanitizing server URLs.
 * Provides security-focused URL validation to prevent:
 * - Open redirect attacks
 * - Connecting to malicious servers
 * - Invalid URL format issues
 */
object UrlValidator {

    /**
     * Result of URL validation
     */
    sealed class ValidationResult {
        data class Valid(val normalizedUrl: String) : ValidationResult()
        data class Invalid(val error: String) : ValidationResult()
    }

    /**
     * Validates and normalizes a server URL.
     *
     * @param url The URL to validate
     * @param requireHttps If true, rejects http:// URLs (default: true)
     * @return ValidationResult indicating if URL is valid and the normalized form
     */
    fun validate(url: String, requireHttps: Boolean = true): ValidationResult {
        val trimmedUrl = url.trim()

        // Check for empty input
        if (trimmedUrl.isBlank()) {
            return ValidationResult.Invalid("Server URL cannot be empty")
        }

        // Normalize the URL - add https:// if no scheme provided
        val normalizedUrl = when {
            trimmedUrl.startsWith("https://") -> trimmedUrl
            trimmedUrl.startsWith("http://") -> {
                if (requireHttps) {
                    return ValidationResult.Invalid("HTTP is not allowed - use HTTPS for security")
                }
                trimmedUrl
            }
            else -> "https://$trimmedUrl"
        }

        // Parse and validate the URL structure
        val uri = try {
            URI(normalizedUrl)
        } catch (e: URISyntaxException) {
            return ValidationResult.Invalid("Invalid URL format: ${e.reason}")
        }

        // Validate scheme
        if (uri.scheme != "https" && (requireHttps || uri.scheme != "http")) {
            return ValidationResult.Invalid("Only HTTPS URLs are allowed")
        }

        // Validate host is present
        val host = uri.host
        if (host.isNullOrBlank()) {
            return ValidationResult.Invalid("Invalid URL: missing hostname")
        }

        // Validate host format - must be a valid domain or IP
        if (!isValidHost(host)) {
            return ValidationResult.Invalid("Invalid hostname format")
        }

        // Block localhost and private IPs in release builds (optional - can be configured)
        // For development, you may want to allow these
        // if (!BuildConfig.DEBUG && isPrivateOrLocalhost(host)) {
        //     return ValidationResult.Invalid("Cannot connect to local or private addresses")
        // }

        // Validate port if specified
        if (uri.port != -1 && (uri.port < 1 || uri.port > 65535)) {
            return ValidationResult.Invalid("Invalid port number")
        }

        // Block URLs with user info (e.g., http://user:pass@host)
        if (uri.userInfo != null) {
            return ValidationResult.Invalid("URLs with credentials are not allowed")
        }

        // Construct the final normalized URL (remove trailing slash for consistency)
        val finalUrl = buildString {
            append(uri.scheme)
            append("://")
            append(uri.host)
            if (uri.port != -1 && uri.port != 443 && uri.port != 80) {
                append(":")
                append(uri.port)
            }
            // Include path if present, but remove trailing slash
            uri.path?.let { path ->
                if (path.isNotEmpty() && path != "/") {
                    append(path.trimEnd('/'))
                }
            }
        }

        return ValidationResult.Valid(finalUrl)
    }

    /**
     * Quick check if a URL looks valid (for real-time validation while typing)
     */
    fun isValidFormat(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return false

        // Must contain at least one dot for domain
        if (!trimmed.contains(".")) return false

        // Basic pattern check
        val urlToCheck = if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
        return try {
            val uri = URI(urlToCheck)
            uri.host != null && uri.host.contains(".")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Validates hostname format
     */
    private fun isValidHost(host: String): Boolean {
        // Check if it's a valid IP address
        if (Patterns.IP_ADDRESS.matcher(host).matches()) {
            return true
        }

        // Check if it's a valid domain name
        if (Patterns.DOMAIN_NAME.matcher(host).matches()) {
            return true
        }

        // Additional check: must have at least one dot (prevents "localhost" style bypasses)
        // and reasonable length
        if (host.length > 253) return false
        if (!host.contains(".")) return false

        // Each label must be valid
        val labels = host.split(".")
        for (label in labels) {
            if (label.isEmpty() || label.length > 63) return false
            if (!label.matches(Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?$"))) {
                // Allow single char labels
                if (label.length == 1 && !label.matches(Regex("^[a-zA-Z0-9]$"))) {
                    return false
                }
            }
        }

        return true
    }

    /**
     * Check if host is localhost or a private IP range
     */
    @Suppress("unused")
    private fun isPrivateOrLocalhost(host: String): Boolean {
        val lowerHost = host.lowercase()

        // Check localhost variants
        if (lowerHost == "localhost" || lowerHost == "127.0.0.1" || lowerHost == "::1") {
            return true
        }

        // Check private IP ranges
        if (Patterns.IP_ADDRESS.matcher(host).matches()) {
            val parts = host.split(".").map { it.toIntOrNull() ?: return false }
            if (parts.size == 4) {
                // 10.0.0.0/8
                if (parts[0] == 10) return true
                // 172.16.0.0/12
                if (parts[0] == 172 && parts[1] in 16..31) return true
                // 192.168.0.0/16
                if (parts[0] == 192 && parts[1] == 168) return true
                // 169.254.0.0/16 (link-local)
                if (parts[0] == 169 && parts[1] == 254) return true
            }
        }

        return false
    }
}
