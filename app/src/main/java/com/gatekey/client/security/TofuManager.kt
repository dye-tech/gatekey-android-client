package com.gatekey.client.security

import com.gatekey.client.data.repository.CertificateTrustRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Manages Trust on First Use (TOFU) certificate pinning decisions.
 *
 * Coordinates between the network layer (TofuInterceptor) and the UI layer
 * to prompt users for trust decisions when encountering new or changed certificates.
 */
@Singleton
class TofuManager @Inject constructor(
    private val certificateTrustRepository: CertificateTrustRepository
) {
    companion object {
        private const val TAG = "TofuManager"
    }

    // Pending trust decisions awaiting user input
    private val pendingDecisions = ConcurrentHashMap<String, Continuation<TrustDecision>>()
    private val decisionMutex = Mutex()

    // Current trust prompt to show in UI
    private val _currentTrustPrompt = MutableStateFlow<TrustPrompt?>(null)
    val currentTrustPrompt: StateFlow<TrustPrompt?> = _currentTrustPrompt

    /**
     * Check if a server's certificate pin is trusted.
     * Returns the validation result.
     */
    fun validateCertificate(hostname: String, currentPin: String): CertificateValidationResult {
        val storedPin = certificateTrustRepository.getStoredPin(hostname)

        return when {
            storedPin == null -> {
                // First time seeing this server
                CertificateValidationResult.FirstUse(hostname, currentPin)
            }
            storedPin == currentPin -> {
                // Pin matches, trusted
                CertificateValidationResult.Trusted(hostname)
            }
            else -> {
                // Pin changed! Potential security issue
                CertificateValidationResult.PinChanged(
                    hostname = hostname,
                    oldPin = storedPin,
                    newPin = currentPin
                )
            }
        }
    }

    /**
     * Request user decision for a certificate trust prompt.
     * This suspends until the user makes a decision via the UI.
     */
    suspend fun requestTrustDecision(prompt: TrustPrompt): TrustDecision {
        return decisionMutex.withLock {
            // Set the current prompt for UI to display
            _currentTrustPrompt.value = prompt

            // Wait for user decision
            suspendCoroutine { continuation ->
                pendingDecisions[prompt.hostname] = continuation
            }
        }
    }

    /**
     * Called by UI when user makes a trust decision.
     */
    suspend fun submitTrustDecision(hostname: String, decision: TrustDecision) {
        // Clear the prompt
        _currentTrustPrompt.value = null

        // Handle the decision
        when (decision) {
            is TrustDecision.Trust -> {
                certificateTrustRepository.trustServer(hostname, decision.pin)
            }
            is TrustDecision.UpdateTrust -> {
                certificateTrustRepository.updateServerPin(hostname, decision.newPin)
            }
            is TrustDecision.Reject -> {
                // Don't store anything
            }
        }

        // Resume the waiting coroutine
        pendingDecisions.remove(hostname)?.resume(decision)
    }

    /**
     * Cancel any pending trust decision (e.g., user navigated away).
     */
    fun cancelPendingDecision(hostname: String) {
        _currentTrustPrompt.value = null
        pendingDecisions.remove(hostname)?.resume(TrustDecision.Reject)
    }

    /**
     * Check if we have a stored trust for this hostname.
     */
    fun hasTrustedPin(hostname: String): Boolean {
        return certificateTrustRepository.hasTrustedPin(hostname)
    }

    /**
     * Update last verified timestamp when connection succeeds.
     */
    suspend fun markVerified(hostname: String) {
        certificateTrustRepository.updateLastVerified(hostname)
    }
}

/**
 * Result of validating a certificate against stored pins.
 */
sealed class CertificateValidationResult {
    /** First time connecting to this server - need user to trust */
    data class FirstUse(val hostname: String, val pin: String) : CertificateValidationResult()

    /** Certificate matches stored pin - trusted */
    data class Trusted(val hostname: String) : CertificateValidationResult()

    /** Certificate pin has changed - potential security issue */
    data class PinChanged(
        val hostname: String,
        val oldPin: String,
        val newPin: String
    ) : CertificateValidationResult()
}

/**
 * Trust prompt to display to the user.
 */
sealed class TrustPrompt {
    abstract val hostname: String
    abstract val pin: String

    /** First time connecting - ask user to trust this server */
    data class FirstConnection(
        override val hostname: String,
        override val pin: String
    ) : TrustPrompt()

    /** Certificate changed - warn user and ask for decision */
    data class CertificateChanged(
        override val hostname: String,
        override val pin: String,
        val oldPin: String
    ) : TrustPrompt()
}

/**
 * User's decision about trusting a certificate.
 */
sealed class TrustDecision {
    /** Trust this certificate (first use) */
    data class Trust(val pin: String) : TrustDecision()

    /** Update trust to new certificate (after change warning) */
    data class UpdateTrust(val newPin: String) : TrustDecision()

    /** Reject/don't trust this certificate */
    object Reject : TrustDecision()
}
