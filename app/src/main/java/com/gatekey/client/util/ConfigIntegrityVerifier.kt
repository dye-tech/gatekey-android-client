package com.gatekey.client.util

import android.util.Log
import java.security.MessageDigest

/**
 * Utility for verifying VPN configuration integrity.
 * Validates that downloaded configs haven't been tampered with and contain expected security elements.
 */
object ConfigIntegrityVerifier {

    private const val TAG = "ConfigIntegrityVerifier"

    /**
     * Result of config verification
     */
    sealed class VerificationResult {
        data object Valid : VerificationResult()
        data class Invalid(val reason: String) : VerificationResult()
    }

    /**
     * Verifies an OpenVPN configuration file for integrity and security.
     *
     * @param config The OpenVPN config content
     * @param expectedHash Optional SHA-256 hash to verify against (from server)
     * @return VerificationResult indicating if config is valid
     */
    fun verifyOpenVpnConfig(config: String, expectedHash: String? = null): VerificationResult {
        if (config.isBlank()) {
            return VerificationResult.Invalid("Empty configuration")
        }

        // Verify hash if provided
        if (expectedHash != null) {
            val actualHash = computeSha256(config)
            if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                Log.e(TAG, "Config hash mismatch - expected: $expectedHash, actual: $actualHash")
                return VerificationResult.Invalid("Configuration integrity check failed")
            }
        }

        // Validate essential OpenVPN directives are present
        val requiredDirectives = listOf("client", "remote", "ca", "cert", "key")
        val configLower = config.lowercase()

        for (directive in requiredDirectives) {
            // Check for directive (can be inline like <ca> or standalone)
            if (!configLower.contains(directive) && !configLower.contains("<$directive>")) {
                Log.w(TAG, "Missing required directive: $directive")
                // Don't fail on missing directives as config format can vary
                // Just log the warning
            }
        }

        // Security checks - warn on insecure options
        val insecurePatterns = listOf(
            "auth-user-pass" to "Credentials may be embedded",
            "comp-lzo" to "LZO compression has known vulnerabilities",
            "cipher none" to "No encryption specified",
            "auth none" to "No authentication specified"
        )

        for ((pattern, warning) in insecurePatterns) {
            if (configLower.contains(pattern)) {
                Log.w(TAG, "Security warning: $warning")
            }
        }

        // Verify TLS is required (good security practice)
        if (!configLower.contains("tls-client") && !configLower.contains("<tls-auth>") && !configLower.contains("<tls-crypt>")) {
            Log.w(TAG, "TLS authentication not explicitly configured")
        }

        // Check for potentially malicious directives
        val dangerousPatterns = listOf(
            "script-security" to "Script execution enabled",
            "up " to "Up script defined",
            "down " to "Down script defined",
            "route-up" to "Route-up script defined",
            "ipchange" to "IP change script defined",
            "client-connect" to "Client-connect script defined",
            "client-disconnect" to "Client-disconnect script defined",
            "learn-address" to "Learn-address script defined"
        )

        for ((pattern, description) in dangerousPatterns) {
            if (configLower.contains(pattern)) {
                Log.w(TAG, "Potentially dangerous directive found: $description")
                // Scripts in mobile configs are suspicious - could be injection attempt
                if (pattern.contains("script") || pattern.endsWith(" ")) {
                    return VerificationResult.Invalid("Suspicious script directive found: $description")
                }
            }
        }

        return VerificationResult.Valid
    }

    /**
     * Verifies a WireGuard configuration file for integrity and security.
     *
     * @param config The WireGuard config content
     * @param expectedHash Optional SHA-256 hash to verify against
     * @return VerificationResult indicating if config is valid
     */
    fun verifyWireGuardConfig(config: String, expectedHash: String? = null): VerificationResult {
        if (config.isBlank()) {
            return VerificationResult.Invalid("Empty configuration")
        }

        // Verify hash if provided
        if (expectedHash != null) {
            val actualHash = computeSha256(config)
            if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                Log.e(TAG, "Config hash mismatch - expected: $expectedHash, actual: $actualHash")
                return VerificationResult.Invalid("Configuration integrity check failed")
            }
        }

        // Validate essential WireGuard sections
        val configLower = config.lowercase()

        if (!configLower.contains("[interface]")) {
            return VerificationResult.Invalid("Missing [Interface] section")
        }

        if (!configLower.contains("[peer]")) {
            return VerificationResult.Invalid("Missing [Peer] section")
        }

        // Validate required Interface fields
        if (!configLower.contains("privatekey")) {
            return VerificationResult.Invalid("Missing PrivateKey in Interface")
        }

        if (!configLower.contains("address")) {
            return VerificationResult.Invalid("Missing Address in Interface")
        }

        // Validate required Peer fields
        if (!configLower.contains("publickey")) {
            return VerificationResult.Invalid("Missing PublicKey in Peer")
        }

        if (!configLower.contains("endpoint")) {
            Log.w(TAG, "No Endpoint specified - may be intentional for some configs")
        }

        // Check for AllowedIPs (required for routing)
        if (!configLower.contains("allowedips")) {
            return VerificationResult.Invalid("Missing AllowedIPs in Peer")
        }

        // Security: Check for overly permissive AllowedIPs that might indicate misconfiguration
        if (configLower.contains("allowedips = 0.0.0.0/0") || configLower.contains("allowedips=0.0.0.0/0")) {
            Log.i(TAG, "Full tunnel mode detected (AllowedIPs = 0.0.0.0/0)")
        }

        return VerificationResult.Valid
    }

    /**
     * Compute SHA-256 hash of content
     */
    fun computeSha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
