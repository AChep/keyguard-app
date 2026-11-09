package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.model.DKeyguardPeerStatus
import com.artemchep.keyguard.common.model.DKeyguardTransport
import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import com.artemchep.keyguard.feature.p2psync.transport.ConnectRole
import com.artemchep.keyguard.feature.p2psync.transport.Transport
import com.artemchep.keyguard.feature.p2psync.transport.TransportKind
import com.artemchep.keyguard.feature.p2psync.transport.TransportPath
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardIceServers
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardTransportContext
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardTransportFactory
import com.artemchep.keyguard.provider.keyguard.transport.NeverOpeningTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which transports carry a session: the ones both devices list in their hellos, raced,
 * WebRTC with a head start. Linked apps with real Noise handshakes over an in-memory relay.
 */
class KeyguardSessionTransportsTest {
    @Test
    fun `a device without WebRTC is reached over the relay with no wait on WebRTC`() = realTime {
        val webRtc = NeverOpeningTransport()
        // The device with the smaller id dials: give it WebRTC, and the other one, a watch, none.
        val (low, high) = List(2) { newTestDevice("Device $it") }.sortedBy { it.deviceId }
        val factory = KeyguardTransportFactory { context ->
            if (context.session.localDeviceId == low.deviceId) {
                listOf(webRtc, context.relayForward)
            } else {
                listOf(context.relayForward)
            }
        }
        KeyguardSessionTestGroup(size = 2, transportFactory = factory, identities = listOf(high, low)).use { group ->
            val (watch, phone) = group.devices
            watch.startLive()
            phone.startLive()
            awaitConnected(phone, watch)
            awaitStatus(phone) { status -> status.transportOf(watch) == DKeyguardTransport.RELAY }
            assertEquals(0, webRtc.attempts.get())
        }
    }

    @Test
    fun `a WebRTC connection that does not open loses the race to the relay`() = realTime {
        val webRtc = NeverOpeningTransport()
        val factory = KeyguardTransportFactory { context -> listOf(webRtc, context.relayForward) }
        KeyguardSessionTestGroup(size = 2, transportFactory = factory).use { group ->
            val (a, b) = group.devices
            a.startLive()
            b.startLive()
            awaitConnected(a, b)
            awaitStatus(a) { status -> status.transportOf(b) == DKeyguardTransport.RELAY }
            // WebRTC was tried, and never had to give up for the relay to start.
            assertTrue(webRtc.attempts.get() > 0)
        }
    }

    @Test
    fun `a session over TURN moves to a new connection before its credentials expire`() = realTime {
        val connections = AtomicInteger()
        val factory = KeyguardTransportFactory { context ->
            listOf(SettledTransport(context, TransportPath.RELAY, connections))
        }
        val group = KeyguardSessionTestGroup(
            size = 2,
            transportFactory = factory,
            iceServers = { turnIceServers(ttlSeconds = 2) },
        )
        group.use {
            val (a, b) = group.devices
            a.startLive()
            b.startLive()
            awaitConnected(a, b)
            awaitStatus(a) { status -> status.transportOf(b) == DKeyguardTransport.TURN }
            // Both devices connect again at once: the peer never shows offline.
            val offline = AtomicInteger()
            val watcher = a.scope.launch {
                a.status().filterNotNull().collect { status ->
                    if (status.peers[b.deviceId] == DKeyguardPeerStatus.Connection.Offline) offline.incrementAndGet()
                }
            }
            // Credentials of 2 seconds: a session lasts 1.8 seconds of them.
            eventually { connections.get() >= 6 }
            a.addForwarder("After a reconnect")
            eventually { "After a reconnect" in b.forwarders() }
            watcher.cancel()
            assertEquals(0, offline.get())
        }
    }

    @Test
    fun `a direct session never moves for its TURN credentials`() = realTime {
        val connections = AtomicInteger()
        val factory = KeyguardTransportFactory { context ->
            listOf(SettledTransport(context, TransportPath.SRFLX, connections))
        }
        val group = KeyguardSessionTestGroup(
            size = 2,
            transportFactory = factory,
            iceServers = { turnIceServers(ttlSeconds = 1) },
        )
        group.use {
            val (a, b) = group.devices
            a.startLive()
            b.startLive()
            awaitConnected(a, b)
            awaitStatus(a) { status -> status.transportOf(b) == DKeyguardTransport.STUN }
            delay(4_000L)
            // One connection, seen from both ends.
            assertEquals(2, connections.get())
        }
    }

    private fun KeyguardSessionStatus.transportOf(peer: SessionTestDevice): DKeyguardTransport? =
        (peers[peer.deviceId] as? DKeyguardPeerStatus.Connection.Connected)?.transport

    /** ICE servers with TURN credentials of [ttlSeconds]. */
    private fun turnIceServers(ttlSeconds: Int): KeyguardIceServers {
        val body = """
            {
              "iceServers": [{ "urls": ["turn:turn.test:3478"], "username": "u", "credential": "c" }],
              "ttlSeconds": $ttlSeconds
            }
        """.trimIndent()
        val client = HttpClient(
            MockEngine {
                respond(body, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            },
        )
        return KeyguardIceServers(client)
    }
}

/**
 * Relay forwarding that passes for a WebRTC connection settled on [path]: it takes ICE
 * servers, as WebRTC does, and reports the path. Counts the [connections] it opened.
 */
private class SettledTransport(
    private val context: KeyguardTransportContext,
    private val path: TransportPath,
    private val connections: AtomicInteger,
) : Transport {
    override val kind: TransportKind get() = TransportKind.WEBRTC

    override suspend fun connect(peerId: String, role: ConnectRole): ByteStream {
        context.session.iceServers()
        val stream = context.relayForward.connect(peerId, role)
        context.session.onSelectedPath(peerId, path)
        connections.incrementAndGet()
        return stream
    }
}
