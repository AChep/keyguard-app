package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.common.service.agent.AgentDuplexChannel
import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import com.artemchep.keyguard.provider.keyguard.transport.TransportException
import kotlin.concurrent.Volatile

/**
 * The frames of an extension's stream, one Noise message each, as the
 * [ByteStream] that `NoiseByteStream` runs over. A frame outside of the
 * stream's range ends it.
 *
 * Closing only ends later reads: the session that owns the stream cancels a
 * pending one, which closes the connection.
 */
internal class BrowserDuplexStream(
    private val channel: AgentDuplexChannel,
) : ByteStream {
    @Volatile
    private var closed = false

    override suspend fun read(): ByteArray? {
        if (closed) return null
        return try {
            channel.readPacket()
        } catch (_: IllegalArgumentException) {
            closed = true
            null
        }
    }

    override suspend fun write(bytes: ByteArray) {
        if (closed || !channel.writePacket(bytes)) {
            closed = true
            throw TransportException("The browser stream closed")
        }
    }

    override suspend fun awaitWritable() = Unit

    override suspend fun close() {
        closed = true
    }
}
