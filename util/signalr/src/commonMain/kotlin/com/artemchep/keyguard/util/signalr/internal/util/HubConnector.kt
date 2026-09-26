package com.artemchep.keyguard.util.signalr.internal.util

import com.artemchep.keyguard.util.signalr.internal.HubConnectionOptions
import com.artemchep.keyguard.util.signalr.internal.Transport
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun connect(
    options: HubConnectionOptions,
): EstablishedConnection {
    val negotiation = negotiate(options)
    val transport = options.httpClient.connectTransport(
        url = negotiation.url,
        headers = negotiation.headers,
        webSocketSessionConnector = options.webSocketSessionConnector,
    )
    try {
        val initialPayload = handshake(
            protocol = options.protocol,
            handshakeResponseTimeout = options.handshakeResponseTimeout,
            transport = transport,
            json = options.json,
        )
        return EstablishedConnection(
            transport = transport,
            connectionId = negotiation.connectionId,
            initialPayload = initialPayload,
        )
    } catch (ex: Throwable) {
        withContext(NonCancellable) {
            runCatching {
                withTimeoutOrNull(options.closeTimeout) {
                    transport.stop()
                }
            }
        }
        throw ex
    }
}

internal data class EstablishedConnection(
    val transport: Transport,
    val connectionId: String?,
    val initialPayload: ByteArray?,
)
