package com.gatekey.client.security

import android.util.Log
import com.gatekey.client.data.repository.CertificateTrustRepository
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.X509TrustManager

/**
 * Custom X509TrustManager that implements TOFU (Trust on First Use) certificate pinning.
 *
 * On first connection to a server, it extracts the certificate pin and throws
 * [CertificateRequiresTrustException] so the UI can prompt the user.
 *
 * On subsequent connections, it verifies the pin matches. If changed, throws
 * [CertificatePinChangedException] to warn the user.
 */
class TofuTrustManager(
    private val defaultTrustManager: X509TrustManager,
    private val certificateTrustRepository: CertificateTrustRepository
) : X509TrustManager {

    companion object {
        private const val TAG = "TofuTrustManager"
    }

    // Temporarily trusted pins (for this session, after user approves but before persisted)
    private val sessionTrustedPins = mutableMapOf<String, String>()

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        defaultTrustManager.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // First, do standard certificate validation
        defaultTrustManager.checkServerTrusted(chain, authType)

        // Then do TOFU pin validation
        if (chain.isNullOrEmpty()) {
            throw CertificateException("Empty certificate chain")
        }

        val leafCert = chain[0]
        val pin = extractPin(leafCert)
        val hostname = extractHostname(leafCert)

        Log.d(TAG, "Validating certificate for $hostname with pin: ${pin.take(20)}...")

        // Check session trust first (user just approved but not yet persisted)
        val sessionPin = sessionTrustedPins[hostname]
        if (sessionPin == pin) {
            Log.d(TAG, "Certificate trusted via session for $hostname")
            return
        }

        // Check stored trust
        val storedPin = certificateTrustRepository.getStoredPin(hostname)

        when {
            storedPin == null -> {
                // First time seeing this server - need user approval
                Log.i(TAG, "First connection to $hostname - requires trust decision")
                throw CertificateRequiresTrustException(hostname, pin)
            }
            storedPin == pin -> {
                // Pin matches, trusted
                Log.d(TAG, "Certificate pin verified for $hostname")
            }
            else -> {
                // Pin changed! Security warning
                Log.w(TAG, "Certificate pin CHANGED for $hostname!")
                throw CertificatePinChangedException(hostname, storedPin, pin)
            }
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        return defaultTrustManager.acceptedIssuers
    }

    /**
     * Temporarily trust a pin for this session.
     * Call this after user approves, before the retry request.
     */
    fun addSessionTrust(hostname: String, pin: String) {
        sessionTrustedPins[hostname.lowercase()] = pin
        Log.d(TAG, "Added session trust for $hostname")
    }

    /**
     * Clear session trust (e.g., after persisting to repository).
     */
    fun clearSessionTrust(hostname: String) {
        sessionTrustedPins.remove(hostname.lowercase())
    }

    /**
     * Extract SHA-256 pin from certificate's public key.
     */
    private fun extractPin(certificate: X509Certificate): String {
        val publicKey = certificate.publicKey.encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
        return "sha256/${Base64.getEncoder().encodeToString(digest)}"
    }

    /**
     * Extract hostname from certificate's CN or SAN.
     */
    private fun extractHostname(certificate: X509Certificate): String {
        // Try to get from Subject Alternative Names first
        try {
            certificate.subjectAlternativeNames?.forEach { san ->
                if (san.size >= 2 && san[0] == 2) { // DNS name type
                    return (san[1] as? String)?.lowercase() ?: ""
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract SAN", e)
        }

        // Fall back to CN
        val dn = certificate.subjectX500Principal.name
        val cnMatch = Regex("CN=([^,]+)").find(dn)
        return cnMatch?.groupValues?.get(1)?.lowercase() ?: "unknown"
    }
}

/**
 * Thrown when connecting to a server for the first time.
 * The UI should prompt the user to trust this server.
 */
class CertificateRequiresTrustException(
    val hostname: String,
    val pin: String
) : CertificateException("First connection to $hostname requires trust decision")

/**
 * Thrown when a server's certificate pin has changed.
 * The UI should warn the user about the potential security issue.
 */
class CertificatePinChangedException(
    val hostname: String,
    val oldPin: String,
    val newPin: String
) : CertificateException("Certificate pin changed for $hostname - potential security issue")
