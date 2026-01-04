package com.gatekey.client.data.api

import com.gatekey.client.data.repository.SettingsRepository
import com.gatekey.client.data.repository.TokenRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interceptor that dynamically rewrites the base URL for each request
 * based on the current server URL setting
 */
@Singleton
class DynamicBaseUrlInterceptor @Inject constructor(
    private val settingsRepository: SettingsRepository
) : Interceptor {

    @Volatile
    private var cachedServerUrl: String? = null

    fun updateServerUrl(url: String) {
        cachedServerUrl = url
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url

        // Get the current server URL from cache or settings
        val serverUrl = cachedServerUrl ?: runBlocking {
            settingsRepository.settings.first().serverUrl.also { cachedServerUrl = it }
        }

        // If no server URL is configured, pass through the request as-is
        // This will fail, but that's expected when no server is configured
        if (serverUrl.isBlank()) {
            return chain.proceed(originalRequest)
        }

        // Parse the server URL
        val baseUrl = serverUrl.trimEnd('/').toHttpUrlOrNull()
            ?: return chain.proceed(originalRequest)

        // Build the new URL by replacing the host and scheme
        val newUrl = originalUrl.newBuilder()
            .scheme(baseUrl.scheme)
            .host(baseUrl.host)
            .port(baseUrl.port)
            .build()

        val newRequest = originalRequest.newBuilder()
            .url(newUrl)
            .build()

        return chain.proceed(newRequest)
    }
}

/**
 * OkHttp interceptor that adds authentication headers to requests
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenRepository: TokenRepository
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Skip auth for auth-related endpoints
        val path = originalRequest.url.encodedPath
        if (path.contains("/auth/providers") ||
            path.contains("/auth/cli/login") ||
            path.contains("/auth/oidc/login") ||
            path.contains("/auth/local/login") ||
            path.contains("/auth/api-key/validate")
        ) {
            return chain.proceed(originalRequest)
        }

        // Get token from repository
        val token = runBlocking { tokenRepository.getToken() }

        return if (token != null && !tokenRepository.isTokenExpired()) {
            val authenticatedRequest = originalRequest.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
            chain.proceed(authenticatedRequest)
        } else {
            chain.proceed(originalRequest)
        }
    }
}

/**
 * Interceptor for handling 401 responses
 */
@Singleton
class AuthenticatorInterceptor @Inject constructor(
    private val tokenRepository: TokenRepository
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        if (response.code == 401) {
            // Token expired or invalid - clear it
            runBlocking {
                tokenRepository.clearToken()
            }
        }

        return response
    }
}
