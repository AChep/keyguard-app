package com.artemchep.keyguard.util.signalr.internal.util

import com.artemchep.keyguard.util.signalr.internal.HubConnectionOptions
import com.artemchep.keyguard.util.signalr.internal.Transport
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal suspend fun connect(
    options: HubConnectionOptions,
): EstablishedConnection {
    var transport: Transport? = null
    try {
        val negotiation = negotiate(options)
        val nextTransport = options.httpClient.connectTransport(
            url = negotiation.url,
            headers = negotiation.headers,
        )
        transport = nextTransport

        val initialPayload = handshake(
            protocol = options.protocol,
            handshakeResponseTimeout = options.handshakeResponseTimeout,
            transport = nextTransport,
            json = options.json,
        )
        return EstablishedConnection(
            transport = nextTransport,
            connectionId = negotiation.connectionId,
            initialPayload = initialPayload,
        )
    } catch (ex: Throwable) {
        withContext(NonCancellable) {
            runCatching {
                transport?.stop()
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
