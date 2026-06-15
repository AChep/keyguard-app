package com.artemchep.keyguard.util.signalr.internal.transports

import com.artemchep.keyguard.util.signalr.internal.Transport
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow

internal class WebSocketTransport(
    private val session: WebSocketSession,
) : Transport {
    override suspend fun send(
        message: ByteArray,
    ) {
        session.send(message)
    }

    override suspend fun sendText(
        message: String,
    ) {
        session.send(message)
    }

    override fun receive(
    ): Flow<ByteArray> = session
        .incoming
        .receiveAsFlow()
        .mapNotNull(Frame::toSignalRPayloadOrNull)

    override suspend fun stop() {
        session.close()
    }
}

@Suppress("REDUNDANT_ELSE_IN_WHEN")
internal fun Frame.toSignalRPayloadOrNull(): ByteArray? = when (this) {
    is Frame.Text -> readText().encodeToByteArray()
    is Frame.Binary -> readBytes()
    is Frame.Close -> null
    is Frame.Ping -> readBytes()
    is Frame.Pong -> null
    else -> null
}
