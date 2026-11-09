package com.artemchep.keyguard.feature.p2pwebrtc

import com.artemchep.keyguard.feature.p2psync.transport.ConnectRole
import com.artemchep.keyguard.feature.p2psync.transport.TransportPath
import com.artemchep.keyguard.util.webrtc.WebRtcConfig
import com.artemchep.keyguard.util.webrtc.WebRtcRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Two devices in one process on 127.0.0.1, signaled in memory. Runs on every target that
 * links the native WebRTC library.
 */
class WebRtcTransportTest {
    private val loopback = WebRtcTransportConfig(bindAddresses = listOf("127.0.0.1:0"))

    @Test
    fun twoDevicesConnectAndExchangeOrderedMessages() = realTime {
        val runtime = WebRtcRuntime.create()
        try {
            coroutineScope {
                val hub = InMemorySignalingHub()
                val paths = MutableStateFlow(emptyList<TransportPath>())
                val alice = transport(runtime, hub, "device-a", this) { _, path -> paths.update { it + path } }
                val bob = transport(runtime, hub, "device-b", this)

                // Bob listens late: the offer repeats until he answers.
                val aliceStream = async { alice.connect("device-b", ConnectRole.INITIATOR) }
                delay(LATE_START_MS)
                val bobStream = withTimeout(TIMEOUT) { bob.connect("device-a", ConnectRole.RESPONDER) }
                val a = withTimeout(TIMEOUT) { aliceStream.await() } as WebRtcByteStream
                val b = bobStream as WebRtcByteStream

                val forward = List(MESSAGES) { i -> message(i, 16 + i * 600) }
                val backward = List(MESSAGES) { i -> message(1_000 + i, 32 * 1024 - i) }
                launch {
                    forward.forEach {
                        a.awaitWritable()
                        a.write(it)
                    }
                }
                launch { backward.forEach { b.write(it) } }
                val receivedByBob = withTimeout(TIMEOUT) { List(MESSAGES) { b.read() } }
                val receivedByAlice = withTimeout(TIMEOUT) { List(MESSAGES) { a.read() } }
                forward.zip(receivedByBob).forEach { (expected, actual) -> assertContentEquals(expected, actual) }
                backward.zip(receivedByAlice).forEach { (expected, actual) -> assertContentEquals(expected, actual) }

                withTimeout(TIMEOUT) { paths.first { it.isNotEmpty() } }
                assertEquals(listOf(TransportPath.HOST), paths.value.distinct())

                // Closing one end ends the other.
                a.close()
                assertNull(withTimeout(TIMEOUT) { b.read() })
                b.close()
            }
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun connectTimesOutWhenSignalingNeverAnswers() = realTime {
        val runtime = WebRtcRuntime.create()
        try {
            coroutineScope {
                val hub = InMemorySignalingHub()
                // Nobody answers the offers of the device with the smaller id.
                val alice = transport(
                    runtime = runtime,
                    hub = hub,
                    id = "device-a",
                    scope = this,
                    config = loopback.copy(connectTimeoutMs = SHORT_TIMEOUT_MS, offerResendMs = 200L),
                )
                val timeout = assertFailsWith<WebRtcTransportException> {
                    alice.connect("device-b", ConnectRole.INITIATOR)
                }
                assertEquals(WebRtcTransportException.Reason.TIMEOUT, timeout.reason)

                // The answering side gives up the same way when no offer arrives, without
                // ever creating a peer: it asks for no ICE servers.
                var iceServersAsked = 0
                val bob = transport(
                    runtime = runtime,
                    hub = hub,
                    id = "device-b",
                    scope = this,
                    config = loopback.copy(connectTimeoutMs = SHORT_TIMEOUT_MS),
                    iceServers = {
                        iceServersAsked++
                        emptyList()
                    },
                )
                val silent = assertFailsWith<WebRtcTransportException> {
                    // The device with the larger id answers; Alice gave up before Bob joined.
                    bob.connect("device-a", ConnectRole.RESPONDER)
                }
                assertEquals(WebRtcTransportException.Reason.TIMEOUT, silent.reason)
                assertEquals(0, iceServersAsked)
            }
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun foreignPayloadsAreNotSignals() {
        val signal = WebRtcSignal(WebRtcSignal.Kind.OFFER, sid = "0123456789abcdef", sdp = "v=0")
        assertEquals(signal, WebRtcSignalCodec.decode(WebRtcSignalCodec.encode(signal)))
        val rejected = listOf(
            "not json",
            """{"t":"other","kind":"offer","sid":"0123456789abcdef","sdp":"v=0"}""",
            // An offer without its SDP.
            """{"t":"webrtc","kind":"offer","sid":"0123456789abcdef"}""",
            """{"t":"webrtc","kind":"answer","sid":"XYZ","sdp":"v=0"}""",
            """{"t":"webrtc","v":2,"kind":"offer","sid":"0123456789abcdef","sdp":"v=0"}""",
        )
        rejected.forEach { text -> assertNull(WebRtcSignalCodec.decode(text.encodeToByteArray()), text) }
    }

    @Suppress("LongParameterList") // Test wiring.
    private fun transport(
        runtime: WebRtcRuntime,
        hub: InMemorySignalingHub,
        id: String,
        scope: CoroutineScope,
        config: WebRtcTransportConfig = loopback,
        iceServers: suspend () -> List<WebRtcConfig.IceServer> = { emptyList() },
        onSelectedPath: (String, TransportPath) -> Unit = { _, _ -> },
    ) = WebRtcTransport(
        runtime = runtime,
        signaling = hub.join(id),
        localDeviceId = id,
        iceServers = iceServers,
        scope = scope,
        config = config,
        onSelectedPath = onSelectedPath,
    )

    private fun message(sequence: Int, size: Int): ByteArray =
        ByteArray(size) { index -> (sequence + index).toByte() }

    private companion object {
        const val MESSAGES = 50
        const val LATE_START_MS = 2_500L
        const val SHORT_TIMEOUT_MS = 2_000L
        val TIMEOUT = 20.seconds
    }
}
