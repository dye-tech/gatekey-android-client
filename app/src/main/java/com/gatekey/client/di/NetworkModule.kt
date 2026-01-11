package com.gatekey.client.di

import android.content.Context
import com.gatekey.client.BuildConfig
import com.gatekey.client.data.api.AuthInterceptor
import com.gatekey.client.data.api.AuthenticatorInterceptor
import com.gatekey.client.data.api.DynamicBaseUrlInterceptor
import com.gatekey.client.data.api.GatekeyApi
import com.gatekey.client.data.api.SanitizingLoggingInterceptor
import com.gatekey.client.data.repository.CertificateTrustRepository
import com.gatekey.client.data.repository.SettingsRepository
import com.gatekey.client.security.TofuSslSocketFactory
import com.gatekey.client.security.TofuTrustManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * TOFU (Trust on First Use) SSL configuration.
     * Provides certificate pinning that works with multiple servers:
     * - First connection: prompts user to trust the server
     * - Subsequent connections: verifies the stored pin
     * - Pin changes: warns user about potential security issue
     */
    @Provides
    @Singleton
    fun provideTofuSslConfig(
        certificateTrustRepository: CertificateTrustRepository
    ): TofuSslSocketFactory.SslConfig {
        return TofuSslSocketFactory.createSslConfig(certificateTrustRepository)
    }

    @Provides
    @Singleton
    fun provideTofuTrustManager(
        sslConfig: TofuSslSocketFactory.SslConfig
    ): TofuTrustManager {
        return sslConfig.trustManager
    }

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return GsonBuilder()
            .setDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .create()
    }

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            // Use BASIC in release builds to avoid logging sensitive data
            // Use HEADERS in debug for more visibility (still excludes body)
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.BASIC
            }
        }
    }

    @Provides
    @Singleton
    fun provideSanitizingLoggingInterceptor(): SanitizingLoggingInterceptor {
        return SanitizingLoggingInterceptor()
    }

    @Provides
    @Singleton
    fun provideDynamicBaseUrlInterceptor(
        settingsRepository: SettingsRepository
    ): DynamicBaseUrlInterceptor {
        return DynamicBaseUrlInterceptor(settingsRepository)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        dynamicBaseUrlInterceptor: DynamicBaseUrlInterceptor,
        authInterceptor: AuthInterceptor,
        authenticatorInterceptor: AuthenticatorInterceptor,
        loggingInterceptor: HttpLoggingInterceptor,
        sanitizingLoggingInterceptor: SanitizingLoggingInterceptor,
        sslConfig: TofuSslSocketFactory.SslConfig
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(dynamicBaseUrlInterceptor)
            .addInterceptor(authInterceptor)
            .addInterceptor(authenticatorInterceptor)
            // Use sanitizing interceptor to redact sensitive headers before logging
            .addInterceptor(sanitizingLoggingInterceptor)
            .addInterceptor(loggingInterceptor)
            // Use TOFU (Trust on First Use) for certificate pinning
            .sslSocketFactory(sslConfig.sslSocketFactory, sslConfig.trustManager)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        gson: Gson
    ): Retrofit {
        // Use a placeholder base URL - the DynamicBaseUrlInterceptor will rewrite it
        return Retrofit.Builder()
            .baseUrl("https://gatekey.placeholder/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    @Provides
    @Singleton
    fun provideGatekeyApi(retrofit: Retrofit): GatekeyApi {
        return retrofit.create(GatekeyApi::class.java)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context = context
}
