package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.model.DKeyguardPeerStatus
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import com.artemchep.keyguard.feature.p2psync.transport.ConnectRole
import com.artemchep.keyguard.feature.p2psync.transport.Transport
import com.artemchep.keyguard.feature.p2psync.transport.TransportKind
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardTransportFactory
import com.artemchep.keyguard.provider.keyguard.transport.TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * How a device's sessions hand over: one connection per device, shared by the app that
 * holds the session and its sync passes; a session that ends says BYE; and a new
 * connection of a peer replaces the session it dropped. Linked apps with real Noise
 * handshakes and the real sync pass on in-memory databases.
 */
class KeyguardSessionHandoverTest {
    @Test
    fun `a sync pass shares the session the app holds`() = realTime {
        KeyguardSessionTestGroup(size = 2).use { group ->
            val (a, b) = group.devices
            a.startLive()
            b.startLive()
            awaitConnected(a, b)
            val clients = group.relay.clients.size

            b.addForwarder("From B")
            b.sync()
            eventually { "From B" in a.forwarders() }
            // The pass went over the held session: no second connection.
            assertEquals(clients, group.relay.clients.size)
        }
    }

    @Test
    fun `a session a pass started outlives it once the app holds it`() = realTime {
        KeyguardSessionTestGroup(size = 2).use { group ->
            val (a, b) = group.devices
            // The pass starts the session: no one holds it yet, and with no peer
            // online it waits a while for one to show up.
            b.addForwarder("From B")
            val pass = async { b.sync() }
            eventually { b.registry.get(P2pGroupId.APP_LINK_ID) != null }
            b.startLive()
            pass.await()
            // The app took over the connection of the pass.
            assertEquals(1, group.relay.clients.size)

            a.startLive()
            eventually { "From B" in a.forwarders() }
            // The held session is live now: what arrives queues a pass.
            a.addForwarder("From A")
            eventually { "From A" in b.forwarders() }
            assertEquals(2, group.relay.clients.size)
        }
    }

    @Test
    fun `a session the app lets go of says BYE, so its peer does not wait for it to time out`() = realTime {
        val switchboard = SilentSwitchboard()
        KeyguardSessionTestGroup(
            size = 2,
            timings = SLOW_DEATH,
            transportFactory = switchboard.factory,
        ).use { group ->
            val (a, b) = group.devices
            a.startLive()
            val held = b.startLive()
            awaitConnected(a, b)

            held.cancelAndJoin()
            val released = TimeSource.Monotonic.markNow()
            awaitStatus(a) { status -> status.peers.values.none { it is DKeyguardPeerStatus.Connection.Connected } }
            assertTrue(released.elapsedNow() < HANDOVER_BOUND, "took ${released.elapsedNow()}")
        }
    }

    @Test
    fun `a pass that ends the session says BYE, so its peer does not wait for it to time out`() = realTime {
        val switchboard = SilentSwitchboard()
        KeyguardSessionTestGroup(
            size = 2,
            timings = SLOW_DEATH,
            transportFactory = switchboard.factory,
        ).use { group ->
            val (a, b) = group.devices
            a.startLive()
            b.addForwarder("From B")

            // No one holds B's session: its pass connects once.
            b.sync()
            val exchanged = TimeSource.Monotonic.markNow()
            awaitStatus(a) { status -> status.peers.values.none { it is DKeyguardPeerStatus.Connection.Connected } }
            assertTrue(exchanged.elapsedNow() < HANDOVER_BOUND, "took ${exchanged.elapsedNow()}")
            eventually { "From B" in a.forwarders() }
        }
    }

    @Test
    fun `a new connection of a peer replaces the session it dropped without a word`() = realTime {
        val switchboard = SilentSwitchboard()
        // The founder has the larger id, so it waits for the other device to dial.
        val (low, high) = List(2) { newTestDevice("Device $it") }.sortedBy { it.deviceId }
        KeyguardSessionTestGroup(
            size = 2,
            timings = SLOW_DEATH,
            transportFactory = switchboard.factory,
            identities = listOf(high, low),
        ).use { group ->
            val (responder, initiator) = group.devices
            responder.startLive()
            // A session that drops without a word, unlike a held one.
            val first = initiator.startBareSession()
            awaitStatus(responder) { status ->
                status.peers[initiator.deviceId] is DKeyguardPeerStatus.Connection.Connected
            }

            // The responder never hears that this session ended.
            first.cancelAndJoin()
            initiator.startLive()
            val restarted = TimeSource.Monotonic.markNow()
            awaitStatus(initiator) { status ->
                status.peers[responder.deviceId] is DKeyguardPeerStatus.Connection.Connected
            }
            assertTrue(restarted.elapsedNow() < HANDOVER_BOUND, "took ${restarted.elapsedNow()}")

            initiator.addForwarder("After the handover")
            initiator.sync()
            eventually { "After the handover" in responder.forwarders() }
        }
    }

    @Test
    fun `a newer connection that never says HELLO does not replace the running session`() = realTime {
        val switchboard = SilentSwitchboard()
        // The founder has the larger id, so it waits for the other device to dial.
        val (low, high) = List(2) { newTestDevice("Device $it") }.sortedBy { it.deviceId }
        KeyguardSessionTestGroup(
            size = 2,
            timings = SLOW_DEATH,
            transportFactory = switchboard.factory,
            identities = listOf(high, low),
        ).use { group ->
            val (responder, initiator) = group.devices
            responder.startLive()
            initiator.startLive()
            awaitConnected(responder, initiator)

            // Someone replays the opening of the initiator, its suite id and first handshake
            // message, as a new connection: the handshake of the responder completes on it.
            val opening = switchboard.openings.value.getValue(initiator.deviceId to responder.deviceId)
            val replay = switchboard.replay(from = initiator.deviceId, to = responder.deviceId, opening)
            assertNotNull(withTimeout(SESSION_TIMEOUT) { replay.read() })

            // No HELLO ever comes on it: the running session goes on, then and after the wait.
            initiator.addForwarder("While the replay waits")
            eventually { "While the replay waits" in responder.forwarders() }
            delay(KeyguardSessionManager.REPLACEMENT_HELLO_TIMEOUT_MS + 1_000L)
            initiator.addForwarder("After the replay")
            eventually { "After the replay" in responder.forwarders() }
            awaitConnected(responder, initiator)
        }
    }

    private companion object {
        /** Far below [SLOW_DEATH]: the handover did not wait for a timeout. */
        val HANDOVER_BOUND = 15.seconds
    }
}

/**
 * TEST ONLY: direct streams between the devices of a test group, as WebRTC data channels:
 * the initiator offers a stream, the responder takes it. A closed stream never tells the
 * other end, as a connection that drops silently; what was written before still arrives.
 */
internal class SilentSwitchboard {
    private class Offer(
        val from: String,
        val to: String,
        val stream: ByteStream,
    ) {
        val taken = CompletableDeferred<Unit>()
    }

    private val offers = MutableStateFlow<List<Offer>>(emptyList())

    /**
     * The first [OPENING_SIZE] messages each device wrote on the first stream it offered, by
     * the device and its peer: the id of its Noise suite and the first message of its
     * handshake.
     */
    val openings = MutableStateFlow<Map<Pair<String, String>, List<ByteArray>>>(emptyMap())

    /**
     * Offers [to] a stream as if [from] dialed it, on which [messages] arrive and nothing
     * more; returns the other end, which reads what [to] writes.
     */
    suspend fun replay(from: String, to: String, messages: List<ByteArray>): ByteStream {
        val (replayer, offered) = SilentStream.pair()
        messages.forEach { replayer.write(it) }
        offers.update { it + Offer(from = from, to = to, stream = offered) }
        return replayer
    }

    val factory = KeyguardTransportFactory { context ->
        listOf(SwitchboardTransport(context.session.localDeviceId))
    }

    private inner class SwitchboardTransport(
        private val self: String,
    ) : Transport {
        override val kind: TransportKind get() = TransportKind.WEBRTC

        override suspend fun connect(peerId: String, role: ConnectRole): ByteStream = when (role) {
            ConnectRole.INITIATOR -> offer(peerId)
            ConnectRole.RESPONDER -> take(peerId)
        }

        private suspend fun offer(peerId: String): ByteStream {
            val (written, theirs) = SilentStream.pair()
            val mine = OpeningStream(written) { opening ->
                openings.update { if ((self to peerId) in it) it else it + ((self to peerId) to opening) }
            }
            val offer = Offer(from = self, to = peerId, stream = theirs)
            offers.update { it + offer }
            try {
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) { offer.taken.await() }
                    ?: throw TransportException("The device did not take the stream")
            } finally {
                offers.update { it - offer }
            }
            return mine
        }

        private suspend fun take(peerId: String): ByteStream = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            offers
                .map { list -> list.firstOrNull { it.from == peerId && it.to == self && it.taken.complete(Unit) } }
                .filterNotNull()
                .first()
                .stream
        } ?: throw TransportException("The device did not offer a stream")
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 2_000L
    }
}

/** TEST ONLY: a stream that hands its first [OPENING_SIZE] written messages to [onOpening]. */
private class OpeningStream(
    private val inner: ByteStream,
    private val onOpening: (List<ByteArray>) -> Unit,
) : ByteStream by inner {
    private val opening = mutableListOf<ByteArray>()

    override suspend fun write(bytes: ByteArray) {
        // The handshake writes one message at a time, so this needs no lock.
        if (opening.size < OPENING_SIZE) {
            opening += bytes
            if (opening.size == OPENING_SIZE) onOpening(opening.toList())
        }
        inner.write(bytes)
    }
}

/** The messages of the opening of a stream: the id of the Noise suite and the first handshake message. */
private const val OPENING_SIZE = 2

/** TEST ONLY: one end of a pipe whose [close] the other end never notices. */
internal class SilentStream private constructor(
    private val incoming: Channel<ByteArray>,
    private val outgoing: Channel<ByteArray>,
) : ByteStream {
    @Volatile
    private var closed = false

    override suspend fun read(): ByteArray? = if (closed) null else incoming.receiveCatching().getOrNull()

    override suspend fun write(bytes: ByteArray) {
        if (closed) throw TransportException("The stream is closed")
        outgoing.trySend(bytes)
    }

    override suspend fun awaitWritable() = Unit

    override suspend fun close() {
        closed = true
        // Ends a pending read here; the other end keeps waiting.
        incoming.close()
    }

    companion object {
        fun pair(): Pair<SilentStream, SilentStream> {
            val ab = Channel<ByteArray>(Channel.UNLIMITED)
            val ba = Channel<ByteArray>(Channel.UNLIMITED)
            return SilentStream(ba, ab) to SilentStream(ab, ba)
        }
    }
}
