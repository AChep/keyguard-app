package com.artemchep.keyguard.provider.keyguard.transport

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// One function per scenario.
@Suppress("TooManyFunctions")
class RelayHubTest {
    @Test
    fun `rooms share one WebSocket and the peer id the relay draws`() = runTest {
        val dialer = FakeDialer()
        val hub = hub(dialer)
        val a = hub.open(ROOM_A)
        val b = hub.open(ROOM_B)
        start(a)
        start(b)
        runCurrent()
        val wire = dialer.wires.receive()
        val self = wire.peer
        // A join names only its room: the welcome named the peer id.
        assertEquals(setOf("t", "room"), wire.nextJoin(ROOM_A).keys)
        wire.nextJoin(ROOM_B)
        assertEquals(RelayState.Connecting, a.client.state.value)

        wire.text(joined(ROOM_A, X))
        runCurrent()
        assertEquals(RelayState.Joined(self, TICKET), a.client.state.value)
        assertEquals(setOf(X), a.client.presence.value)
        assertEquals(RelayState.Connecting, b.client.state.value)
        assertNotConnected { b.client.publish(Y, byteArrayOf(1)) }

        wire.text(joined(ROOM_B, Y))
        wire.text(presence(ROOM_B, Z, "join"))
        wire.text(presence(ROOM_A, X, "leave"))
        runCurrent()
        assertEquals(RelayState.Joined(self, TICKET), b.client.state.value)
        assertEquals(setOf(Y, Z), b.client.presence.value)
        assertEquals(emptySet(), a.client.presence.value)

        // Messages reach the room they were published in.
        val aMessages = collect(a.client.messages)
        val bMessages = collect(b.client.messages)
        wire.text(msg(ROOM_B, Y, byteArrayOf(2)))
        wire.text(msg(ROOM_A, Z, byteArrayOf(1)))
        runCurrent()
        assertEquals(listOf(Z), aMessages.map { it.from })
        assertEquals(listOf(Y), bMessages.map { it.from })
        assertContentEquals(byteArrayOf(2), bMessages.single().data)

        // A publication names its room.
        b.client.publish(Y, byteArrayOf(5))
        runCurrent()
        val pub = wire.next()
        assertEquals("pub", pub.field("t"))
        assertEquals(ROOM_B, pub.field("room"))
        assertEquals(Y, pub.field("to"))
        assertEquals(1, dialer.dials)
    }

    @Test
    fun `a forwarded frame reaches only its room`() = runTest {
        val dialer = FakeDialer()
        val hub = hub(dialer)
        val a = hub.open(ROOM_A)
        val b = hub.open(ROOM_B)
        start(a)
        start(b)
        runCurrent()
        val wire = dialer.wires.receive()
        wire.nextJoin(ROOM_A)
        wire.nextJoin(ROOM_B)
        wire.text(joined(ROOM_A, X, Z))
        wire.text(joined(ROOM_B, Y, Z))
        val aFrames = collect(a.client.forwarded)
        val bFrames = collect(b.client.forwarded)
        runCurrent()

        wire.binary(ROOM_A, X, byteArrayOf(1))
        wire.binary(ROOM_B, Y, byteArrayOf(2))
        // Z is in both rooms: each frame reaches the room it names.
        wire.binary(ROOM_A, Z, byteArrayOf(3))
        wire.binary(ROOM_B, Z, byteArrayOf(4))
        // Not from a peer of the room it names, or from a room this WebSocket is not in: dropped.
        wire.binary(ROOM_A, Y, byteArrayOf(5))
        wire.binary(ROOM_A, W, byteArrayOf(6))
        wire.binary(ROOM_C, X, byteArrayOf(7))
        runCurrent()
        assertEquals(listOf(X to 1, Z to 3), aFrames.map { it.from to it.payload.single().toInt() })
        assertEquals(listOf(Y to 2, Z to 4), bFrames.map { it.from to it.payload.single().toInt() })
        assertEquals(setOf(ROOM_A), aFrames.map { it.room }.toSet())
        assertEquals(setOf(ROOM_B), bFrames.map { it.room }.toSet())

        a.client.forward(X, byteArrayOf(9))
        runCurrent()
        val forward = wire.fromClient.tryReceive().getOrThrow()
        assertIs<Frame.Binary>(forward)
        assertContentEquals(RelayCodec.forward(ROOM_A, X, byteArrayOf(9)), forward.readBytes())
    }

    @Test
    fun `the WebSocket closes after its last room stops`() = runTest {
        val dialer = FakeDialer()
        val hub = hub(dialer)
        val a = hub.open(ROOM_A)
        val b = hub.open(ROOM_B)
        val aJob = start(a)
        val bJob = start(b)
        runCurrent()
        val wire = dialer.wires.receive()
        wire.nextJoin(ROOM_A)
        wire.nextJoin(ROOM_B)
        wire.text(joined(ROOM_A))
        wire.text(joined(ROOM_B))
        runCurrent()

        aJob.cancel()
        runCurrent()
        val leave = wire.next()
        assertEquals("leave", leave.field("t"))
        assertEquals(ROOM_A, leave.field("room"))
        assertEquals(RelayState.Disconnected(null), a.client.state.value)
        assertNotConnected { a.client.publish(X, byteArrayOf(1)) }
        assertFalse(wire.closed.isCompleted)
        assertTrue(b.client.state.value is RelayState.Joined)

        bJob.cancel()
        runCurrent()
        assertTrue(wire.closed.isCompleted)

        // The next room opens a new one.
        start(hub.open(ROOM_C))
        runCurrent()
        assertEquals(2, dialer.dials)
        dialer.wires.receive().nextJoin(ROOM_C)
    }

    @Test
    fun `a reconnect rejoins every room under a new peer id`() = runTest {
        val dialer = FakeDialer()
        val hub = hub(dialer)
        val a = hub.open(ROOM_A)
        val b = hub.open(ROOM_B)
        start(a)
        start(b)
        runCurrent()
        val wire = dialer.wires.receive()
        wire.nextJoin(ROOM_A)
        wire.nextJoin(ROOM_B)
        wire.text(joined(ROOM_A, X))
        wire.text(joined(ROOM_B, Y))
        runCurrent()
        assertNotNull(a.client.forwardCredit)

        wire.close(code = 1013)
        runCurrent()
        assertEquals(RelayState.Disconnected(RelayError.SlowConsumer), a.client.state.value)
        assertEquals(RelayState.Disconnected(RelayError.SlowConsumer), b.client.state.value)
        assertEquals(emptySet(), a.client.presence.value)
        assertNull(a.client.forwardCredit)
        assertNotConnected { a.client.publish(X, byteArrayOf(1)) }
        assertEquals(1, dialer.dials)

        advanceTimeBy(MAX_FIRST_BACKOFF_MS)
        runCurrent()
        val next = dialer.wires.receive()
        assertNotEquals(wire.peer, next.peer)
        next.nextJoin(ROOM_A)
        next.nextJoin(ROOM_B)
        assertEquals(RelayState.Connecting, a.client.state.value)
        next.text(joined(ROOM_A, X))
        runCurrent()
        assertEquals(RelayState.Joined(next.peer, TICKET), a.client.state.value)
    }

    @Test
    fun `a refused join waits on its own`() = runTest {
        val dialer = FakeDialer()
        val hub = hub(dialer)
        val a = hub.open(ROOM_A)
        val b = hub.open(ROOM_B)
        start(a)
        start(b)
        runCurrent()
        val wire = dialer.wires.receive()
        val self = wire.peer
        wire.nextJoin(ROOM_A)
        wire.nextJoin(ROOM_B)
        val aErrors = collect(a.client.errors)
        val bErrors = collect(b.client.errors)
        runCurrent()

        wire.text(err("room_full", ROOM_A))
        wire.text(joined(ROOM_B, Y))
        runCurrent()
        assertEquals(RelayState.Disconnected(RelayError.RoomFull), a.client.state.value)
        assertEquals(listOf<RelayError>(RelayError.RoomFull), aErrors)
        assertEquals(emptyList(), bErrors)
        assertEquals(RelayState.Joined(self, TICKET), b.client.state.value)

        // The same WebSocket asks again after a backoff.
        advanceTimeBy(MAX_FIRST_BACKOFF_MS)
        runCurrent()
        wire.nextJoin(ROOM_A)
        assertEquals(RelayState.Connecting, a.client.state.value)
        wire.text(joined(ROOM_A, X))
        runCurrent()
        assertEquals(RelayState.Joined(self, TICKET), a.client.state.value)
        assertFalse(wire.closed.isCompleted)
        assertEquals(1, dialer.dials)
    }

    @Test
    fun `a join over the rooms of an address backs off on the same WebSocket`() = runTest {
        val dialer = FakeDialer()
        val hub = hub(dialer)
        val a = hub.open(ROOM_A)
        val b = hub.open(ROOM_B)
        start(a)
        start(b)
        runCurrent()
        val wire = dialer.wires.receive()
        wire.nextJoin(ROOM_A)
        wire.nextJoin(ROOM_B)
        wire.text(joined(ROOM_A))
        wire.text(err("rate_limited", ROOM_B))
        runCurrent()
        assertEquals(RelayState.Disconnected(RelayError.RateLimited), b.client.state.value)
        assertTrue(a.client.state.value is RelayState.Joined)

        // No other WebSocket: it would count toward the same address.
        advanceTimeBy(MAX_FIRST_BACKOFF_MS)
        runCurrent()
        wire.nextJoin(ROOM_B)
        assertEquals(1, dialer.dials)
        assertFalse(wire.closed.isCompleted)
    }

    @Test
    fun `an unknown type is no reason to reconnect, and unknown frames are ignored`() = runTest {
        val dialer = FakeDialer()
        val hub = hub(dialer)
        val a = hub.open(ROOM_A)
        start(a)
        runCurrent()
        val wire = dialer.wires.receive()
        wire.nextJoin(ROOM_A)
        wire.text(joined(ROOM_A, X))
        val errors = collect(a.client.errors)
        val messages = collect(a.client.messages)
        runCurrent()

        wire.text(err("unknown_type"))
        wire.text("""{"t":"news","room":"$ROOM_A","x":[1]}""")
        // A second welcome means nothing: the peer id stays.
        wire.text(welcome(Y))
        wire.text(msg(ROOM_A, X, byteArrayOf(1)))
        runCurrent()
        assertEquals(listOf<RelayError>(RelayError.UnknownType), errors)
        assertEquals(listOf(X), messages.map { it.from })
        assertEquals(RelayState.Joined(wire.peer, TICKET), a.client.state.value)
        assertFalse(wire.closed.isCompleted)
        assertEquals(1, dialer.dials)
    }

    @Test
    fun `a relay of another version, or without forwarding, is incompatible`() = runTest {
        val greetings = listOf(
            welcome(X, version = 2),
            welcome(X, features = "[\"room-forward-v2\"]"),
            // Too small for a session frame.
            welcome(X, limits = """{"maxMessageBytes":1024}"""),
            // A limit that is not positive.
            welcome(X, limits = """{"queuedFrames":0}"""),
            // Not a welcome first.
            """{"t":"pong"}""",
            joined(ROOM_A),
        )
        for (greeting in greetings) {
            val dialer = FakeDialer(greeting = { greeting })
            val hub = hub(dialer)
            val a = hub.open(ROOM_A)
            val job = start(a)
            runCurrent()
            val wire = dialer.wires.receive()
            assertTrue(wire.closed.isCompleted, greeting)
            assertEquals(RelayState.Disconnected(RelayError.Incompatible), a.client.state.value, greeting)
            // Nothing went to it.
            assertTrue(wire.fromClient.isEmpty, greeting)
            job.cancel()
        }
    }

    @Test
    fun `a relay that refuses the token says so before the welcome`() = runTest {
        val dialer = FakeDialer(greeting = { err("unauthorized") })
        val hub = hub(dialer)
        val a = hub.open(ROOM_A)
        start(a)
        runCurrent()
        assertTrue(dialer.wires.receive().closed.isCompleted)
        assertEquals(RelayState.Disconnected(RelayError.Unauthorized), a.client.state.value)
    }

    @Test
    fun `a relay that sends no welcome times out`() = runTest {
        val dialer = FakeDialer(greeting = { null })
        val hub = hub(dialer)
        val a = hub.open(ROOM_A)
        start(a)
        runCurrent()
        val wire = dialer.wires.receive()
        advanceTimeBy(RelayHub.JOIN_TIMEOUT_MS + 1)
        runCurrent()
        assertTrue(wire.closed.isCompleted)
        assertEquals(RelayState.Disconnected(RelayError.Timeout), a.client.state.value)
    }

    @Test
    fun `the limits of the welcome apply to the connection`() = runTest {
        val limits = """{"maxMessageBytes":40000,"queuedFrames":40,"idleSeconds":60,"future":7}"""
        val dialer = FakeDialer(greeting = { peer -> welcome(peer, limits = limits) })
        val hub = hub(dialer)
        val a = hub.open(ROOM_A)
        start(a)
        runCurrent()
        val wire = dialer.wires.receive()
        wire.nextJoin(ROOM_A)
        wire.text(joined(ROOM_A, X))
        runCurrent()
        val expected = RelayLimits(maxMessageBytes = 40_000, queuedFrames = 40)
        assertEquals(RelayState.Joined(wire.peer, TICKET, expected), a.client.state.value)
        // A quarter of the queue stays free for the other frames.
        assertEquals(30, a.client.forwardCredit?.total)

        // The relay's message size, not the protocol's.
        val tooBig = assertFailsWith<RelayException> { a.client.forward(X, ByteArray(40_000)) }
        assertEquals(RelayError.FrameTooBig, tooBig.error)
        a.client.forward(X, ByteArray(40_000 - RelayCodec.FORWARD_HEADER_SIZE))
        assertFailsWith<RelayException> { a.client.publish(X, ByteArray(30_000)) }
    }

    @Test
    fun `a room holds no more peers than the welcome allows`() = runTest {
        val dialer = FakeDialer(greeting = { peer -> welcome(peer, limits = """{"peersPerRoom":3}""") })
        val hub = hub(dialer)
        val a = hub.open(ROOM_A)
        start(a)
        runCurrent()
        val wire = dialer.wires.receive()
        wire.nextJoin(ROOM_A)
        wire.text(joined(ROOM_A, X, Y, Z))
        runCurrent()
        assertEquals(setOf(X, Y), a.client.presence.value)

        wire.text(presence(ROOM_A, W, "join"))
        runCurrent()
        assertEquals(setOf(X, Y), a.client.presence.value)
        wire.text(presence(ROOM_A, X, "leave"))
        wire.text(presence(ROOM_A, W, "join"))
        runCurrent()
        assertEquals(setOf(Y, W), a.client.presence.value)
    }

    @Test
    fun `the welcome sets the pace of the connection`() = runTest {
        val dialer = FakeDialer(greeting = { peer -> welcome(peer, limits = """{"messagesPerSecond":5}""") })
        val hub = hub(dialer)
        val a = hub.open(ROOM_A)
        start(a)
        runCurrent()
        val wire = dialer.wires.receive()
        wire.nextJoin(ROOM_A)
        wire.text(joined(ROOM_A, X))
        runCurrent()

        // 4 messages per second: 0.8 of the relay's 5, after a burst of 2 the join took one of.
        backgroundScope.launch { repeat(9) { a.client.publish(X, byteArrayOf(1)) } }
        advanceTimeBy(1_000L)
        runCurrent()
        val sentInASecond = generateSequence { wire.fromClient.tryReceive().getOrNull() }.count()
        assertTrue(sentInASecond in 4..6, "sent $sentInASecond")
    }

    @Test
    fun `an unanswered join ends the connection`() = runTest {
        val dialer = FakeDialer()
        val hub = hub(dialer)
        val a = hub.open(ROOM_A)
        val b = hub.open(ROOM_B)
        start(a)
        start(b)
        runCurrent()
        val wire = dialer.wires.receive()
        wire.nextJoin(ROOM_A)
        wire.nextJoin(ROOM_B)
        wire.text(joined(ROOM_A))
        runCurrent()

        advanceTimeBy(RelayHub.JOIN_TIMEOUT_MS - 1)
        runCurrent()
        assertFalse(wire.closed.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(wire.closed.isCompleted)
        assertEquals(RelayState.Disconnected(RelayError.Timeout), a.client.state.value)
        assertEquals(RelayState.Disconnected(RelayError.Timeout), b.client.state.value)
    }

    @Test
    fun `pings keep the connection, silence ends it`() = runTest {
        val dialer = FakeDialer()
        val hub = hub(dialer)
        val a = hub.open(ROOM_A)
        start(a)
        runCurrent()
        val wire = dialer.wires.receive()
        wire.nextJoin(ROOM_A)
        wire.text(joined(ROOM_A))
        runCurrent()

        // A third of the relay's 60 s idle timeout.
        assertEquals(RelayHub.PING_INTERVAL_MS, RelayHub.pingIntervalMs(RelayLimits()))
        advanceTimeBy(RelayHub.PING_INTERVAL_MS + 1)
        assertEquals("ping", wire.next().field("t"))
        wire.text("""{"t":"pong"}""")
        runCurrent()
        // The pings 20 s and 40 s after the pong are within the silence timeout, 60 s is not.
        repeat(2) {
            advanceTimeBy(RelayHub.PING_INTERVAL_MS)
            assertEquals("ping", wire.next().field("t"))
        }
        assertFalse(wire.closed.isCompleted)
        advanceTimeBy(RelayHub.PING_INTERVAL_MS)
        runCurrent()
        assertTrue(wire.closed.isCompleted)
        assertEquals(RelayState.Disconnected(RelayError.Timeout), a.client.state.value)
    }

    @Test
    fun `a relay with a short idle timeout gets pinged more often`() = runTest {
        val dialer = FakeDialer(greeting = { peer -> welcome(peer, limits = """{"idleSeconds":6}""") })
        val hub = hub(dialer)
        val a = hub.open(ROOM_A)
        start(a)
        runCurrent()
        val wire = dialer.wires.receive()
        wire.nextJoin(ROOM_A)
        wire.text(joined(ROOM_A))
        runCurrent()

        advanceTimeBy(2_001L)
        assertEquals("ping", wire.next().field("t"))
        wire.text("""{"t":"pong"}""")
        advanceTimeBy(2_000L)
        assertEquals("ping", wire.next().field("t"))
        assertFalse(wire.closed.isCompleted)
    }

    @Test
    fun `more rooms than a WebSocket holds open another`() = runTest {
        val dialer = FakeDialer()
        val hub = hub(dialer)
        val rooms = List(RelayHub.MAX_ROOMS_PER_SOCKET + 1) { i -> room(i) }
        val jobs = rooms.map { start(hub.open(it)) }
        runCurrent()
        val first = dialer.wires.receive()
        val second = dialer.wires.receive()
        rooms.dropLast(1).forEach { first.nextJoin(it) }
        second.nextJoin(rooms.last())
        assertNotEquals(first.peer, second.peer)

        jobs.last().cancel()
        runCurrent()
        assertTrue(second.closed.isCompleted)
        assertFalse(first.closed.isCompleted)
    }

    @Test
    fun `a welcome with fewer rooms per connection moves the other rooms`() = runTest {
        val dialer = FakeDialer(greeting = { peer -> welcome(peer, limits = """{"roomsPerConnection":2}""") })
        val hub = hub(dialer)
        val rooms = listOf(ROOM_A, ROOM_B, ROOM_C)
        val clients = rooms.map { hub.open(it) }
        clients.forEach { start(it) }
        runCurrent()
        val first = dialer.wires.receive()
        first.nextJoin(ROOM_A)
        first.nextJoin(ROOM_B)
        assertTrue(first.fromClient.isEmpty)
        val second = dialer.wires.receive()
        second.nextJoin(ROOM_C)
        second.text(joined(ROOM_C))
        runCurrent()
        val limits = RelayLimits(roomsPerConnection = 2)
        assertEquals(RelayState.Joined(second.peer, TICKET, limits), clients[2].client.state.value)
    }

    @Test
    fun `a relay with fewer rooms per connection gets more WebSockets`() = runTest {
        val dialer = FakeDialer()
        val hub = hub(dialer)
        val a = hub.open(ROOM_A)
        val b = hub.open(ROOM_B)
        val c = hub.open(ROOM_C)
        listOf(a, b, c).forEach { start(it) }
        runCurrent()
        val first = dialer.wires.receive()
        first.nextJoin(ROOM_A)
        first.nextJoin(ROOM_B)
        first.nextJoin(ROOM_C)
        first.text(joined(ROOM_A))
        first.text(joined(ROOM_B))
        first.text(err("too_many_rooms", ROOM_C))
        runCurrent()

        val second = dialer.wires.receive()
        second.nextJoin(ROOM_C)
        assertNotEquals(first.peer, second.peer)
        second.text(joined(ROOM_C))
        runCurrent()
        assertEquals(RelayState.Joined(second.peer, TICKET), c.client.state.value)

        // The first one is full.
        start(hub.open(ROOM_D))
        runCurrent()
        second.nextJoin(ROOM_D)
        assertTrue(first.fromClient.isEmpty)
    }

    @Test
    fun `two clients of one room use two WebSockets`() = runTest {
        val dialer = FakeDialer()
        val hub = hub(dialer)
        start(hub.open(ROOM_A))
        start(hub.open(ROOM_A))
        runCurrent()
        val first = dialer.wires.receive()
        val second = dialer.wires.receive()
        first.nextJoin(ROOM_A)
        second.nextJoin(ROOM_A)
        assertNotEquals(first.peer, second.peer)
    }

    @Test
    fun `errors without a room reach every room`() = runTest {
        val dialer = FakeDialer()
        val hub = hub(dialer)
        val a = hub.open(ROOM_A)
        val b = hub.open(ROOM_B)
        start(a)
        start(b)
        runCurrent()
        val wire = dialer.wires.receive()
        wire.nextJoin(ROOM_A)
        wire.nextJoin(ROOM_B)
        wire.text(joined(ROOM_A))
        wire.text(joined(ROOM_B))
        val aErrors = collect(a.client.errors)
        val bErrors = collect(b.client.errors)
        runCurrent()

        wire.text(err("rate_limited"))
        runCurrent()
        assertEquals(listOf<RelayError>(RelayError.RateLimited), aErrors)
        assertEquals(listOf<RelayError>(RelayError.RateLimited), bErrors)

        // The error the relay sent before closing wins over the close code.
        wire.text(err("unauthorized"))
        wire.close(code = 1008)
        runCurrent()
        assertEquals(RelayState.Disconnected(RelayError.Unauthorized), a.client.state.value)
        assertEquals(RelayState.Disconnected(RelayError.Unauthorized), b.client.state.value)
    }

    @Test
    fun `a stopped room does not hold up the others`() = runTest {
        val dialer = FakeDialer()
        val hub = hub(dialer)
        val a = hub.open(ROOM_A)
        val b = hub.open(ROOM_B)
        val aJob = start(a)
        start(b)
        runCurrent()
        val wire = dialer.wires.receive()
        wire.nextJoin(ROOM_A)
        wire.nextJoin(ROOM_B)
        wire.text(joined(ROOM_A, X))
        wire.text(joined(ROOM_B, Y))
        val bMessages = collect(b.client.messages)
        runCurrent()

        // Nobody collects room A: its buffer fills and the reader waits.
        repeat(INBOUND_CAPACITY + 1) { wire.text(msg(ROOM_A, X, byteArrayOf(1))) }
        wire.text(msg(ROOM_B, Y, byteArrayOf(2)))
        runCurrent()
        assertEquals(emptyList(), bMessages)

        aJob.cancel()
        runCurrent()
        assertEquals(listOf(Y), bMessages.map { it.from })
    }

    @Test
    fun `a room that comes back skips the answer to its old join`() = runTest {
        val dialer = FakeDialer()
        val hub = hub(dialer)
        start(hub.open(ROOM_B))
        val aJob = start(hub.open(ROOM_A))
        runCurrent()
        val wire = dialer.wires.receive()
        val self = wire.peer
        wire.nextJoin(ROOM_B)
        wire.nextJoin(ROOM_A)
        aJob.cancel()
        runCurrent()
        assertEquals("leave", wire.next().field("t"))
        val a = hub.open(ROOM_A)
        start(a)
        runCurrent()
        wire.nextJoin(ROOM_A)

        // The relay answers the old join, the leave drops the frames of that seat, then
        // it answers the new join.
        wire.text(joined(ROOM_A, X))
        wire.text(msg(ROOM_A, X, byteArrayOf(1)))
        val messages = collect(a.client.messages)
        runCurrent()
        assertEquals(RelayState.Connecting, a.client.state.value)
        assertEquals(emptyList(), messages)

        wire.text(joined(ROOM_A, Y))
        runCurrent()
        assertEquals(RelayState.Joined(self, TICKET), a.client.state.value)
        assertEquals(setOf(Y), a.client.presence.value)
    }

    @Test
    fun `a new room does not wait out the backoff`() = runTest {
        val dialer = FakeDialer()
        dialer.refusals += RelayError.TooManyConnections
        val hub = hub(dialer)
        val a = hub.open(ROOM_A)
        start(a)
        runCurrent()
        assertEquals(RelayState.Disconnected(RelayError.TooManyConnections), a.client.state.value)
        assertEquals(1, dialer.dials)

        start(hub.open(ROOM_B))
        runCurrent()
        assertEquals(2, dialer.dials)
        val wire = dialer.wires.receive()
        wire.nextJoin(ROOM_A)
        wire.nextJoin(ROOM_B)
    }

    private fun TestScope.hub(dialer: FakeDialer): RelayHub = RelayHub(
        dialer = dialer,
        scope = backgroundScope,
        nowMs = { testScheduler.currentTime },
    )

    private fun TestScope.start(connection: KeyguardRelayConnection): Job =
        backgroundScope.launch { connection.run() }

    private fun <T> TestScope.collect(flow: Flow<T>): List<T> {
        val values = mutableListOf<T>()
        backgroundScope.launch { flow.collect { values += it } }
        return values
    }

    private suspend fun assertNotConnected(block: suspend () -> Unit) {
        val e = assertFailsWith<RelayException> { block() }
        assertEquals(RelayError.NotConnected, e.error)
    }

    /** The relay side of a WebSocket, whose connection the relay named [peer]. */
    private class FakeWire(val peer: String) : RelayWire {
        val toClient = Channel<Frame>(Channel.UNLIMITED)
        val fromClient = Channel<Frame>(Channel.UNLIMITED)
        val closed = CompletableDeferred<Unit>()

        private var reason: CloseReason? = null

        override val incoming: ReceiveChannel<Frame> get() = toClient

        override suspend fun send(frame: Frame) {
            fromClient.send(frame)
        }

        override suspend fun closeReason(): CloseReason? = reason

        fun text(text: String) {
            toClient.trySend(Frame.Text(text))
        }

        /** A frame the relay forwards from [from] in [room]. */
        fun binary(room: String, from: String, payload: ByteArray) {
            toClient.trySend(Frame.Binary(true, RelayCodec.forward(room, from, payload)))
        }

        fun close(code: Short) {
            reason = CloseReason(code, "")
            toClient.close()
        }

        /** The next text frame the client sent. */
        fun next(): JsonObject {
            val frame = assertIs<Frame.Text>(fromClient.tryReceive().getOrThrow())
            return Json.parseToJsonElement(frame.readText()).jsonObject
        }

        /** Takes the join of [room]. */
        fun nextJoin(room: String): JsonObject {
            val join = next()
            assertEquals("join", join.field("t"))
            assertEquals(room, join.field("room"))
            return join
        }
    }

    /**
     * Opens a [FakeWire] per dial, under a new peer id, on which the relay first sends what
     * [greeting] returns for that id, if anything; or refuses it with the next of [refusals].
     */
    private class FakeDialer(
        private val greeting: (peer: String) -> String? = { peer -> welcome(peer) },
    ) : RelayDialer {
        val wires = Channel<FakeWire>(Channel.UNLIMITED)
        val refusals = ArrayDeque<RelayError>()
        var dials = 0

        override suspend fun dial(session: suspend (RelayWire) -> RelayError?): RelayError? {
            dials += 1
            val refusal = refusals.removeFirstOrNull()
            if (refusal != null) return refusal
            val wire = FakeWire(peer = (0x10 + dials).toString(16).repeat(16))
            greeting(wire.peer)?.let(wire::text)
            wires.send(wire)
            try {
                return session(wire)
            } finally {
                wire.closed.complete(Unit)
            }
        }
    }

    private companion object {
        val ROOM_A = room(0xa)
        val ROOM_B = room(0xb)
        val ROOM_C = room(0xc)
        val ROOM_D = room(0xd)

        val X = "7a".repeat(16)
        val Y = "7b".repeat(16)
        val Z = "7c".repeat(16)
        val W = "7d".repeat(16)

        val TICKET = "e1".repeat(16)

        const val INBOUND_CAPACITY = 64

        /** The first backoff: 1 s with a 20 % jitter. */
        const val MAX_FIRST_BACKOFF_MS = 1_201L

        fun room(i: Int): String = (0x40 + i).toString(16).repeat(32)

        fun welcome(
            peer: String,
            version: Int = 1,
            features: String = "[\"room-forward\",\"future\"]",
            limits: String = "{}",
        ): String = """{"t":"welcome","v":$version,"peer":"$peer","features":$features,"limits":$limits}"""

        fun joined(room: String, vararg peers: String): String {
            val list = peers.joinToString(",") { "\"$it\"" }
            return """{"t":"joined","room":"$room","peers":[$list],"ticket":"$TICKET"}"""
        }

        fun presence(room: String, peer: String, state: String): String =
            """{"t":"presence","room":"$room","peer":"$peer","state":"$state"}"""

        fun msg(room: String, from: String, data: ByteArray): String =
            """{"t":"msg","room":"$room","from":"$from","data":"${Base64.Default.encode(data)}"}"""

        fun err(code: String, room: String? = null): String =
            if (room == null) """{"t":"err","code":"$code"}""" else """{"t":"err","code":"$code","room":"$room"}"""

        fun JsonObject.field(name: String): String = getValue(name).jsonPrimitive.content
    }
}
