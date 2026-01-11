package com.gatekey.client.security

import android.util.Log
import com.gatekey.client.data.repository.CertificateTrustRepository
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Factory for creating SSL components with TOFU trust management.
 */
object TofuSslSocketFactory {

    private const val TAG = "TofuSslSocketFactory"

    /**
     * Creates a TofuTrustManager that wraps the system default trust manager.
     */
    fun createTofuTrustManager(
        certificateTrustRepository: CertificateTrustRepository
    ): TofuTrustManager {
        val trustManagerFactory = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        )
        trustManagerFactory.init(null as KeyStore?)

        val defaultTrustManager = trustManagerFactory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
            ?: throw IllegalStateException("No X509TrustManager found")

        return TofuTrustManager(defaultTrustManager, certificateTrustRepository)
    }

    /**
     * Creates an SSLSocketFactory that uses the TOFU trust manager.
     */
    fun createSslSocketFactory(
        tofuTrustManager: TofuTrustManager
    ): SSLSocketFactory {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(tofuTrustManager), SecureRandom())
        return sslContext.socketFactory
    }

    /**
     * Helper class to hold both the socket factory and trust manager.
     */
    data class SslConfig(
        val sslSocketFactory: SSLSocketFactory,
        val trustManager: TofuTrustManager
    )

    /**
     * Create a complete SSL configuration for OkHttp with TOFU.
     */
    fun createSslConfig(
        certificateTrustRepository: CertificateTrustRepository
    ): SslConfig {
        val trustManager = createTofuTrustManager(certificateTrustRepository)
        val socketFactory = createSslSocketFactory(trustManager)

        Log.d(TAG, "Created TOFU SSL configuration")

        return SslConfig(socketFactory, trustManager)
    }
}
