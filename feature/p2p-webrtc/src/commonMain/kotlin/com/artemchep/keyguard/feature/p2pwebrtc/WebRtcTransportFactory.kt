package com.artemchep.keyguard.feature.p2pwebrtc

import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import com.artemchep.keyguard.feature.p2psync.transport.ConnectRole
import com.artemchep.keyguard.feature.p2psync.transport.Transport
import com.artemchep.keyguard.feature.p2psync.transport.TransportFactory
import com.artemchep.keyguard.feature.p2psync.transport.TransportKind
import com.artemchep.keyguard.feature.p2psync.transport.TransportSessionContext
import com.artemchep.keyguard.util.webrtc.WebRtcConfig
import com.artemchep.keyguard.util.webrtc.WebRtcIceTransportPolicy
import com.artemchep.keyguard.util.webrtc.WebRtcRuntime
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The [TransportFactory] of the platforms that link WebRTC: one [WebRtcTransport] per
 * session. A session starts its WebRTC runtime with its first connection and shuts it
 * down when the session's scope ends.
 *
 * Without [TransportSessionContext.allowLocalNetwork], connections go through TURN only.
 */
class WebRtcTransportFactory(
    private val config: WebRtcTransportConfig = WebRtcTransportConfig(),
    private val createRuntime: suspend () -> WebRtcRuntime = { WebRtcRuntime.create() },
) : TransportFactory {
    override fun create(context: TransportSessionContext): List<Transport> = listOf(SessionTransport(context))

    private inner class SessionTransport(
        private val context: TransportSessionContext,
    ) : Transport {
        override val kind: TransportKind = TransportKind.WEBRTC

        private val mutex = Mutex()

        private var transport: WebRtcTransport? = null

        override suspend fun connect(peerId: String, role: ConnectRole): ByteStream =
            transport().connect(peerId, role)

        private suspend fun transport(): WebRtcTransport = mutex.withLock {
            transport ?: start().also { transport = it }
        }

        private suspend fun start(): WebRtcTransport {
            val runtime = createRuntime()
            // Atomic, so the runtime shuts down even if the session ended meanwhile.
            context.scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    awaitCancellation()
                } finally {
                    runtime.shutdown()
                }
            }
            val policy = if (context.allowLocalNetwork) {
                config.iceTransportPolicy
            } else {
                WebRtcIceTransportPolicy.RELAY
            }
            return WebRtcTransport(
                runtime = runtime,
                signaling = context.signaling,
                localDeviceId = context.localDeviceId,
                iceServers = {
                    context.iceServers().map { server ->
                        WebRtcConfig.IceServer(
                            urls = server.urls,
                            username = server.username,
                            credential = server.credential,
                        )
                    }
                },
                scope = context.scope,
                config = config.copy(iceTransportPolicy = policy),
                onSelectedPath = context.onSelectedPath,
            )
        }
    }
}
