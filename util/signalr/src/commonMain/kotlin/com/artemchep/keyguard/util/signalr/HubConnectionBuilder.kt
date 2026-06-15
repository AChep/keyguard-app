package com.artemchep.keyguard.util.signalr

import com.artemchep.keyguard.util.signalr.internal.DefaultHubConnection
import com.artemchep.keyguard.util.signalr.internal.HubConnectionOptions
import com.artemchep.keyguard.util.signalr.logger.Logger
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

fun hubConnection(
    url: String,
    httpClient: HttpClient,
    configure: HubConnectionConfig.() -> Unit = {},
): HubConnection {
    val config = HubConnectionConfig()
        .apply(configure)
    val options = HubConnectionOptions.create(
        url = url,
        httpClient = httpClient,
        config = config,
    )
    return DefaultHubConnection(options)
}

class HubConnectionConfig {
    private companion object {
        const val DEFAULT_SERVER_TIMEOUT = 30 * 1000L
        const val DEFAULT_KEEP_ALIVE_INTERVAL = 15 * 1000L
        const val DEFAULT_EVENT_BUFFER_CAPACITY = 64
    }

    var protocol: HubProtocol? = null
    var skipNegotiate: Boolean = false
    var accessTokenProvider: (suspend () -> String)? = null
    var handshakeResponseTimeout: Duration = 10.seconds
    var headers: Map<String, String> = emptyMap()
    var json: Json = Json
    var logger: Logger = Logger.Empty
    var serverTimeout: Duration = DEFAULT_SERVER_TIMEOUT.milliseconds
    var keepAliveInterval: Duration = DEFAULT_KEEP_ALIVE_INTERVAL.milliseconds
    var closeTimeout: Duration = 5.seconds
    var eventBufferCapacity: Int = DEFAULT_EVENT_BUFFER_CAPACITY
}
