package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.feature.p2psync.P2pJson
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.crypto.NativeCipherEncryptor
import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.feature.p2psync.transport.SignalingMessage
import com.artemchep.keyguard.feature.p2psync.transport.TransportKind
import com.artemchep.keyguard.provider.keyguard.session.realTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

// One function per scenario.
@Suppress("TooManyFunctions")
class RelaySignalingTest {
    private val crypto = NativeCryptoGenerator()
    private val cipher = NativeCipherEncryptor(crypto, Base64ServiceImpl())
    private val groupKey = ByteArray(32) { (it * 7).toByte() }
    private val room = RelayRoom.forInvite(groupKey, crypto)

    private class Device(
        val id: String,
        val client: TestRelayClient,
        val signaling: RelaySignaling,
        val received: Channel<SignalingMessage>,
    )

    @Test
    fun `a room restarted on the same relay connection is greeted again without a hello loop`() = realTime {
        val hub = TestRelay().room()
        val aClient = hub.join()
        val bClient = hub.join()
        fun signaling(client: TestRelayClient, id: String) = RelaySignaling(
            client = client,
            room = room,
            localDeviceId = id,
            cipherEncryptor = cipher,
            randomBytes = crypto::seed,
            transports = { setOf(TransportKind.RELAY) },
        )
        var a = signaling(aClient, "device-a")
        val b = signaling(bClient, "device-b")
        var aJob = launch { a.run() }
        val bJob = launch { b.run() }
        try {
            withTimeout(TIMEOUT) { a.presence.first { "device-b" in it } }
            withTimeout(TIMEOUT) { b.presence.first { "device-a" in it } }
            delay(QUIET)
            aJob.cancelAndJoin()
            // B retains its greeting history and both relay ids stay unchanged.
            a = signaling(aClient, "device-a")
            aJob = launch { a.run() }
            withTimeout(TIMEOUT) { a.presence.first { "device-b" in it } }
            assertEquals(bClient.id, a.relayPeerOf("device-b"))
            delay(QUIET)
            val count = hub.published.size
            delay(QUIET)
            assertEquals(count, hub.published.size, "HELLO replies must not feed back into more replies")
        } finally {
            aJob.cancelAndJoin()
            bJob.cancelAndJoin()
        }
    }

    @Test
    fun `a relay connection is one device, the one it named last`() = realTime {
        val hub = TestRelay().room()
        val aClient = hub.join()
        val bClient = hub.join()
        fun signaling(client: TestRelayClient, id: String) = RelaySignaling(
            client = client,
            room = room,
            localDeviceId = id,
            cipherEncryptor = cipher,
            randomBytes = crypto::seed,
            transports = { setOf(TransportKind.RELAY) },
        )
        val b = signaling(bClient, "device-b")
        val bJob = launch { b.run() }
        try {
            // One connection names one device after another, as a member that holds the
            // key of the room could.
            listOf("device-x", "device-y", "device-z").forEach { id ->
                val a = signaling(aClient, id)
                val aJob = launch { a.run() }
                withTimeout(TIMEOUT) { b.presence.first { id in it } }
                aJob.cancelAndJoin()
                assertEquals(setOf(id), b.presence.value)
            }
        } finally {
            bJob.cancelAndJoin()
        }
    }

    @Test
    fun `devices find each other and exchange sealed messages`() = realTime {
        val hub = TestRelay().room()
        withDevices(hub, "device-a", "device-b") { (a, b) ->
            awaitPresence(a, setOf("device-b"))
            awaitPresence(b, setOf("device-a"))
            assertEquals(b.client.id, a.signaling.relayPeerOf("device-b"))

            a.signaling.send("device-b", byteArrayOf(1, 2, 3))
            val message = withTimeout(TIMEOUT) { b.received.receive() }
            assertEquals("device-a", message.from)
            assertContentEquals(byteArrayOf(1, 2, 3), message.payload)

            // The relay carries ciphertext only.
            val wire = hub.lastTo(b).data.decodeToString()
            assertEquals(true, wire.startsWith("2."))
            assertEquals(false, wire.contains("device-a"))
        }
    }

    @Test
    fun `a replayed message is ignored`() = realTime {
        val hub = TestRelay().room()
        withDevices(hub, "device-a", "device-b") { (a, b) ->
            awaitPresence(a, setOf("device-b"))
            a.signaling.send("device-b", byteArrayOf(7))
            withTimeout(TIMEOUT) { b.received.receive() }

            hub.inject(b.client, hub.lastTo(b))
            assertNull(withTimeoutOrNull(QUIET) { b.received.receive() })
        }
    }

    @Test
    fun `a message with a nonce of other than 16 bytes is ignored`() = realTime {
        val hub = TestRelay().room()
        withDevices(hub, "device-a", "device-b") { (a, b) ->
            awaitPresence(a, setOf("device-b"))
            fun sealed(nonce: String): RelayMessage {
                val envelope = SignalingEnvelope(
                    v = RelaySignaling.VERSION,
                    from = "device-a",
                    to = "device-b",
                    kind = Kind.SIGNAL,
                    relay = a.client.id,
                    relayTo = b.client.id,
                    nonce = nonce,
                    body = P2pEncoding.base64Url(byteArrayOf(5)),
                )
                val data = cipher.encode2(
                    cipherType = CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
                    plainText = P2pJson.encodeToString(SignalingEnvelope.serializer(), envelope).encodeToByteArray(),
                    symmetricCryptoKey = room.signalingKey,
                )
                return RelayMessage(a.client.id, data.encodeToByteArray())
            }
            hub.inject(b.client, sealed(P2pEncoding.base64Url(ByteArray(16) { 1 })))
            withTimeout(TIMEOUT) { b.received.receive() }

            // Every nonce of a connection is kept: a long one would take the memory.
            hub.inject(b.client, sealed("A".repeat(30_000)))
            hub.inject(b.client, sealed(P2pEncoding.base64Url(ByteArray(17) { 2 })))
            assertNull(withTimeoutOrNull(QUIET) { b.received.receive() })
        }
    }

    @Test
    fun `tampered or misaddressed or foreign messages are ignored`() = realTime {
        // The first device of each hub gets the same relay id: only the key differs.
        val foreign = run {
            val otherHub = TestRelay().room()
            val otherRoom = RelayRoom.forInvite(ByteArray(32) { 1 }, crypto)
            var sealed: RelayMessage? = null
            withDevices(otherHub, "device-a", "device-b", room = otherRoom) { (a, b) ->
                awaitPresence(a, setOf("device-b"))
                a.signaling.send("device-b", byteArrayOf(9))
                withTimeout(TIMEOUT) { b.received.receive() }
                sealed = otherHub.lastTo(b)
            }
            checkNotNull(sealed)
        }
        val hub = TestRelay().room()
        withDevices(hub, "device-a", "device-b", "device-c") { (a, b, c) ->
            awaitPresence(a, setOf("device-b", "device-c"))
            a.signaling.send("device-b", byteArrayOf(9))
            withTimeout(TIMEOUT) { b.received.receive() }
            val original = hub.lastTo(b)

            // A flipped bit fails the MAC.
            val tampered = original.data.copyOf().also { bytes ->
                val index = bytes.size - 10
                bytes[index] = (if (bytes[index] == 'A'.code.toByte()) 'B' else 'A').code.toByte()
            }
            hub.inject(b.client, RelayMessage(original.from, tampered))
            // Another relay peer replaying the sealed message.
            hub.inject(b.client, RelayMessage(c.client.id, original.data))
            // A message for B delivered to C.
            hub.inject(c.client, original)
            // A message sealed under another group's key, and one not sealed at all.
            assertEquals(a.client.id, foreign.from)
            hub.inject(b.client, foreign)
            hub.inject(b.client, RelayMessage(a.client.id, "garbage".encodeToByteArray()))
            assertNull(withTimeoutOrNull(QUIET) { b.received.receive() })
            assertNull(withTimeoutOrNull(QUIET) { c.received.receive() })
        }
    }

    @Test
    fun `a message for an old connection of this device is ignored`() = realTime {
        val hub = TestRelay().room()
        withDevices(hub, "device-a", "device-b") { (a, b) ->
            awaitPresence(a, setOf("device-b"))
            a.signaling.send("device-b", byteArrayOf(1))
            withTimeout(TIMEOUT) { b.received.receive() }
            val old = hub.lastTo(b)
            val oldId = b.client.id

            // B reconnects under a new relay id; A's message names the old one.
            hub.leave(b.client)
            hub.enter(b.client)
            assertNotEquals(oldId, b.client.id)
            hub.inject(b.client, old)
            assertNull(withTimeoutOrNull(QUIET) { b.received.receive() })

            // A hears from B's new connection, and reaches it there.
            withTimeout(TIMEOUT) { a.signaling.relayPeers.first { it["device-b"] == b.client.id } }
            a.signaling.send("device-b", byteArrayOf(2))
            assertContentEquals(byteArrayOf(2), withTimeout(TIMEOUT) { b.received.receive() }.payload)
        }
    }

    @Test
    fun `a message from a relay id that is not in the room is ignored`() = realTime {
        val hub = TestRelay().room()
        withDevices(hub, "device-a", "device-b", "device-c") { (a, b, c) ->
            awaitPresence(c, setOf("device-b"))
            c.signaling.send("device-b", byteArrayOf(3))
            withTimeout(TIMEOUT) { b.received.receive() }
            // The message of C: A may send B its own meanwhile.
            val sealed = hub.lastTo(b, from = c)

            // C left: the same message, under its relay id, comes from nobody in the room.
            hub.leave(c.client)
            withTimeout(TIMEOUT) { b.client.presence.first { sealed.from !in it } }
            hub.inject(b.client, sealed)
            assertNull(withTimeoutOrNull(QUIET) { b.received.receive() })
            // A message to a device that is not in the room does not go out.
            withTimeout(TIMEOUT) { a.signaling.presence.first { "device-c" !in it } }
            assertFailsWith<TransportException> { a.signaling.send("device-c", byteArrayOf(4)) }
        }
    }

    @Test
    fun `a hello lists the transports of its device`() = realTime {
        val hub = TestRelay().room()
        val transports = mapOf(
            "device-a" to setOf(TransportKind.WEBRTC, TransportKind.RELAY),
            "device-b" to setOf(TransportKind.RELAY),
        )
        withDevices(hub, "device-a", "device-b", transports = transports) { (a, b) ->
            awaitPresence(a, setOf("device-b"))
            awaitPresence(b, setOf("device-a"))
            assertEquals(setOf(TransportKind.RELAY), a.signaling.transportsOf("device-b"))
            assertEquals(setOf(TransportKind.WEBRTC, TransportKind.RELAY), b.signaling.transportsOf("device-a"))
            assertNull(a.signaling.transportsOf("device-z"))
        }
        // Names this build does not know are skipped.
        assertEquals(TransportKind.RELAY, transportKindOf("relay"))
        assertNull(transportKindOf("quic"))
        TransportKind.entries.forEach { assertEquals(it, transportKindOf(transportName(it))) }
    }

    private suspend fun awaitPresence(device: Device, expected: Set<String>) {
        withTimeout(TIMEOUT) { device.signaling.presence.first { it.containsAll(expected) } }
    }

    /** The last message the relay sent to [device], of [from] if given. */
    private fun TestRelayRoom.lastTo(device: Device, from: Device? = null): RelayMessage =
        synchronized(published) {
            published.last { (to, message) ->
                to == device.client.id && (from == null || message.from == from.client.id)
            }.second
        }

    private suspend fun withDevices(
        hub: TestRelayRoom,
        vararg ids: String,
        room: RelayRoom = this.room,
        transports: Map<String, Set<TransportKind>> = emptyMap(),
        block: suspend CoroutineScope.(List<Device>) -> Unit,
    ) = coroutineScope {
        val devices = ids.map { id ->
            val client = hub.join()
            val signaling = RelaySignaling(
                client = client,
                room = room,
                localDeviceId = id,
                cipherEncryptor = cipher,
                randomBytes = crypto::seed,
                transports = { transports[id] ?: setOf(TransportKind.RELAY) },
            )
            Device(id, client, signaling, Channel(Channel.UNLIMITED))
        }
        val jobs = devices.flatMap { device ->
            listOf(
                launch { device.signaling.run() },
                launch { device.signaling.incoming.collect { device.received.send(it) } },
            )
        }
        try {
            block(devices)
        } finally {
            jobs.forEach { it.cancel() }
        }
    }

    private companion object {
        const val TIMEOUT = 10_000L
        const val QUIET = 500L
    }
}
