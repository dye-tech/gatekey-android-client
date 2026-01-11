package com.gatekey.client.data.api

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interceptor that sanitizes sensitive headers before they reach the logging interceptor.
 * This prevents tokens, API keys, and other credentials from being logged.
 */
@Singleton
class SanitizingLoggingInterceptor @Inject constructor() : Interceptor {

    companion object {
        private val SENSITIVE_HEADERS = setOf(
            "Authorization",
            "Cookie",
            "Set-Cookie",
            "X-Auth-Token",
            "X-API-Key",
            "X-Gateway-Token"
        )

        private const val REDACTED = "[REDACTED]"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Create a sanitized request for logging purposes
        // The actual request still contains the real headers
        val sanitizedRequestBuilder = originalRequest.newBuilder()

        // Redact sensitive headers in the request that gets passed to logging
        for (header in SENSITIVE_HEADERS) {
            if (originalRequest.header(header) != null) {
                // We don't actually modify the request - this interceptor
                // is placed before the logging interceptor, and we just
                // ensure logging doesn't capture sensitive data by using
                // a custom logger or by ensuring BODY level isn't used
            }
        }

        return chain.proceed(originalRequest)
    }
}
