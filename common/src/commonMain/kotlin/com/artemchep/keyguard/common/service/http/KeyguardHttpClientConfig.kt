package com.artemchep.keyguard.common.service.http

import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.provider.bitwarden.api.BitwardenPersona
import com.artemchep.keyguard.provider.bitwarden.api.builder.configureBitwardenHttpRetry
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.CurlUserAgent
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import kotlinx.serialization.json.Json

/**
 * Installs the plugins shared by the app's HTTP clients. The engine
 * stays platform-specific and is configured by the caller.
 */
fun HttpClientConfig<*>.configureKeyguardHttpClient(
    json: Json,
    userAgent: KeyguardHttpUserAgent,
) {
    when (userAgent) {
        KeyguardHttpUserAgent.Bitwarden -> install(UserAgent) {
            agent = BitwardenPersona.of(CurrentPlatform)
                .userAgent
        }

        KeyguardHttpUserAgent.Curl -> CurlUserAgent()
    }
    install(ContentNegotiation) {
        register(ContentType.Application.Json, KotlinxSerializationConverter(json))
    }
    install(WebSockets) {
        pingIntervalMillis = HTTP_PING_INTERVAL_MS
    }
    install(HttpCache) {
        // In memory.
    }
    install(HttpRequestRetry) {
        configureBitwardenHttpRetry()
    }
}

private const val HTTP_PING_INTERVAL_MS = 20_000L
