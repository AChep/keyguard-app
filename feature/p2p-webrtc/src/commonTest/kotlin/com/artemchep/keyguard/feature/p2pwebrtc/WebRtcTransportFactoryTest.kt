package com.artemchep.keyguard.feature.p2pwebrtc

import com.artemchep.keyguard.feature.p2psync.transport.ConnectRole
import com.artemchep.keyguard.feature.p2psync.transport.Transport
import com.artemchep.keyguard.feature.p2psync.transport.TransportKind
import com.artemchep.keyguard.feature.p2psync.transport.TransportPath
import com.artemchep.keyguard.feature.p2psync.transport.TransportSessionContext
import com.artemchep.keyguard.util.webrtc.WebRtcRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalAtomicApi::class)
class WebRtcTransportFactoryTest {
    private val runtimes = AtomicInt(0)

    private val factory = WebRtcTransportFactory(
        config = WebRtcTransportConfig(
            bindAddresses = listOf("127.0.0.1:0"),
            connectTimeoutMs = SHORT_TIMEOUT_MS,
        ),
        createRuntime = {
            runtimes.incrementAndFetch()
            WebRtcRuntime.create()
        },
    )

    @Test
    fun eachSessionGetsAWebRtcTransportThatConnects() = realTime {
        val hub = InMemorySignalingHub()
        val paths = MutableStateFlow(emptyList<TransportPath>())
        val sessionA = CoroutineScope(Dispatchers.Default + Job())
        val sessionB = CoroutineScope(Dispatchers.Default + Job())
        try {
            val a = transport(hub, "device-a", sessionA) { _, path -> paths.update { it + path } }
            val b = transport(hub, "device-b", sessionB)
            assertEquals(TransportKind.WEBRTC, a.kind)

            val connecting = async { a.connect("device-b", ConnectRole.INITIATOR) }
            val streamB = withTimeout(TIMEOUT) { b.connect("device-a", ConnectRole.RESPONDER) }
            val streamA = withTimeout(TIMEOUT) { connecting.await() }
            streamA.write(MESSAGE)
            assertContentEquals(MESSAGE, withTimeout(TIMEOUT) { streamB.read() })
            assertEquals(TransportPath.HOST, withTimeout(TIMEOUT) { paths.first { it.isNotEmpty() } }.first())
            // One runtime per session, started by its first connection.
            assertEquals(2, runtimes.load())

            streamA.close()
            streamB.close()
        } finally {
            // Ending the sessions shuts their runtimes down.
            sessionA.coroutineContext.job.cancelAndJoin()
            sessionB.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun withoutTheLocalNetworkOnlyTurnIsTried() = realTime {
        val hub = InMemorySignalingHub()
        val sessionA = CoroutineScope(Dispatchers.Default + Job())
        val sessionB = CoroutineScope(Dispatchers.Default + Job())
        try {
            val a = transport(hub, "device-a", sessionA, allowLocalNetwork = false)
            val b = transport(hub, "device-b", sessionB, allowLocalNetwork = false)
            // No TURN server is configured, so there is nothing to connect through; the
            // session falls back to the relay.
            assertFails { a.connect("device-b", ConnectRole.INITIATOR) }
            assertFails { b.connect("device-a", ConnectRole.RESPONDER) }
        } finally {
            sessionA.coroutineContext.job.cancelAndJoin()
            sessionB.coroutineContext.job.cancelAndJoin()
        }
    }

    private fun transport(
        hub: InMemorySignalingHub,
        id: String,
        scope: CoroutineScope,
        allowLocalNetwork: Boolean = true,
        onSelectedPath: (String, TransportPath) -> Unit = { _, _ -> },
    ): Transport = factory
        .create(
            TransportSessionContext(
                localDeviceId = id,
                signaling = hub.join(id),
                iceServers = { emptyList() },
                scope = scope,
                allowLocalNetwork = allowLocalNetwork,
                onSelectedPath = onSelectedPath,
            ),
        )
        .single()

    private companion object {
        const val SHORT_TIMEOUT_MS = 5_000L
        val TIMEOUT = 20.seconds
        val MESSAGE = "hello".encodeToByteArray()
    }
}
