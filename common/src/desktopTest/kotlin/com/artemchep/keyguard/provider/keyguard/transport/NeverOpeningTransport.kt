package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import com.artemchep.keyguard.feature.p2psync.transport.ConnectRole
import com.artemchep.keyguard.feature.p2psync.transport.Transport
import com.artemchep.keyguard.feature.p2psync.transport.TransportKind
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.atomic.AtomicInteger

/** TEST ONLY: a transport of [kind] that never opens a stream, as WebRTC that no candidate pair gets through. */
internal class NeverOpeningTransport(
    override val kind: TransportKind = TransportKind.WEBRTC,
) : Transport {
    /** How many times it was asked to connect. */
    val attempts = AtomicInteger()

    override suspend fun connect(peerId: String, role: ConnectRole): ByteStream {
        attempts.incrementAndGet()
        awaitCancellation()
    }
}
