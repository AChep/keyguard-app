package com.artemchep.keyguard.util.signalr.internal

import com.artemchep.keyguard.util.signalr.HubConnectionConfig
import com.artemchep.keyguard.util.signalr.HubProtocol
import com.artemchep.keyguard.util.signalr.logger.Logger
import com.artemchep.keyguard.util.signalr.internal.protocols.JsonHubProtocol
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal data class HubConnectionOptions(
    val baseUrl: String,
    val protocol: HubProtocol,
    val httpClient: HttpClient,
    val handshakeResponseTimeout: Duration,
    val headers: Map<String, String>,
    val accessTokenProvider: (suspend () -> String)?,
    val skipNegotiate: Boolean,
    val serverTimeout: Duration,
    val keepAliveInterval: Duration,
    val closeTimeout: Duration,
    val eventBufferCapacity: Int,
    val logger: Logger,
    val json: Json,
) {
    companion object {
        fun create(
            url: String,
            httpClient: HttpClient,
            config: HubConnectionConfig,
        ): HubConnectionOptions {
            return HubConnectionOptions(
                baseUrl = url
                    .takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("A valid url is required."),
                protocol = config.protocol
                    ?: JsonHubProtocol(config.logger),
                httpClient = httpClient,
                handshakeResponseTimeout = if (config.handshakeResponseTimeout.isPositive()) {
                    config.handshakeResponseTimeout
                } else {
                    15.seconds
                },
                headers = config.headers.toMap(),
                accessTokenProvider = config.accessTokenProvider,
                skipNegotiate = config.skipNegotiate,
                serverTimeout = config.serverTimeout,
                keepAliveInterval = config.keepAliveInterval,
                closeTimeout = if (config.closeTimeout.isPositive()) {
                    config.closeTimeout
                } else {
                    5.seconds
                },
                eventBufferCapacity = config.eventBufferCapacity
                    .coerceAtLeast(1),
                logger = config.logger,
                json = config.json,
            )
        }
    }
}
