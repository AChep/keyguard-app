@file:OptIn(ExperimentalCoroutinesApi::class)

package com.artemchep.keyguard.feature.p2psync.session

import com.artemchep.keyguard.feature.p2psync.P2pJson
import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.clock.VersionVector
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.model.CanonicalJson
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.EntryVersion
import com.artemchep.keyguard.feature.p2psync.model.Envelope
import com.artemchep.keyguard.feature.p2psync.model.EnvelopeCodec
import com.artemchep.keyguard.feature.p2psync.model.LocalEnvelope
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.testing.IdentityCompressor
import com.artemchep.keyguard.feature.p2psync.testing.ForwarderBodies
import com.artemchep.keyguard.feature.p2psync.testing.SimDevice
import com.artemchep.keyguard.feature.p2psync.testing.StreamKnobs
import com.artemchep.keyguard.feature.p2psync.testing.TestCrypto
import com.artemchep.keyguard.feature.p2psync.testing.TestGroup
import com.artemchep.keyguard.feature.p2psync.testing.nextUuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncSessionStateMachineTest {
    private val random = Random(17)
    private val crypto = TestCrypto(17)

    private fun TestScope.nodes(group: TestGroup): List<SessionNode> {
        val now = { BASE_TIME + testScheduler.currentTime }
        return group.keys.map { keys -> SessionNode(SimDevice(keys.id, group, now), now) }
    }

    private fun SessionNode.create(name: String): String {
        val id = random.nextUuid()
        device.edit(id, ForwarderBodies.forwarder(name))
        return id
    }

    @Test
    fun `two devices converge`() = runTest {
        val (a, b) = nodes(TestGroup(crypto, size = 2, random = random))
        val first = a.create("one")
        b.create("two")
        val (linkA, linkB) = SessionLink.connect(backgroundScope, a, b)
        advanceTimeBy(5_000)
        assertTrue(linkA.machine.isEstablished && linkB.machine.isEstablished)
        assertEquals(2, a.device.entries.size)
        assertEquals(a.device.entries, b.device.entries)

        // A later edit travels too.
        a.device.edit(first, ForwarderBodies.rename(a.device.view.getValue(first), "uno"))
        a.broadcast()
        advanceTimeBy(1_000)
        assertEquals(a.device.entries, b.device.entries)
        assertEquals("\"uno\"", b.device.view.getValue(first)["name"].toString())
        assertNull(linkA.closeReason)
    }

    @Test
    fun `a settled session reports that both sides are up to date`() = runTest {
        val (a, b) = nodes(TestGroup(crypto, size = 2, random = random))
        a.create("one")
        val (linkA, linkB) = SessionLink.connect(backgroundScope, a, b)
        advanceTimeBy(5_000)
        assertTrue(linkA.machine.isPeerUpToDate && linkA.machine.isSettled)
        assertTrue(linkB.machine.isPeerUpToDate && linkB.machine.isSettled)
        assertEquals(linkA.machine.localHash, linkB.machine.localHash)

        // A local change the peer has not acknowledged yet.
        a.create("two")
        linkA.handle(linkA.machine.onLocalEnvelopeChanged(a.device.localEnvelope()))
        assertTrue(!linkA.machine.isPeerUpToDate && !linkA.machine.isSettled)
        advanceTimeBy(1_000)
        assertTrue(linkA.machine.isPeerUpToDate && linkA.machine.isSettled)
    }

    @Test
    fun `a session is not settled while a roster or envelope of the peer is still arriving`() {
        listOf(FrameType.ENVELOPE, FrameType.ROSTER).forEach { type ->
            val group = TestGroup(crypto, size = 2, random = random)
            val machine = machine(group)
            val local = founderEnvelope(group)
            machine.start(BASE_TIME, local)
            deliver(machine, FrameType.HELLO, hello(group))
            val ack = HashPayload.serializer().encodePayload(HashPayload(local.contentHash))
            deliver(machine, FrameType.ENVELOPE_ACK, ack)
            assertTrue(machine.isSettled && machine.isExchangeDone)

            // The next state of the peer arrives frame by frame; heartbeats say nothing of it.
            val payload = when (type) {
                FrameType.ROSTER -> Roster.serializer().encodePayload(group.roster)
                else -> EnvelopeCodec.encode(Envelope(entries = emptyList()))
            }
            val frames = FrameCodec.split(type, nextMsgId++, payload, maxPayload = 8)
            assertTrue(frames.size > 2)
            frames.dropLast(1).forEach { machine.onFrame(FrameCodec.encode(it)) }
            deliver(machine, FrameType.HEARTBEAT, ByteArray(0))
            assertTrue(machine.isReceiving, "$type")
            assertTrue(machine.isPeerUpToDate, "$type")
            assertFalse(machine.isSettled || machine.isExchangeDone, "$type")

            machine.onFrame(FrameCodec.encode(frames.last()))
            assertFalse(machine.isReceiving, "$type")
            assertTrue(machine.isSettled && machine.isExchangeDone, "$type")
        }
    }

    @Test
    fun `three devices converge through the one in the middle`() = runTest {
        val (a, b, c) = nodes(TestGroup(crypto, size = 3, random = random))
        a.create("from a")
        c.create("from c")
        SessionLink.connect(backgroundScope, a, b)
        SessionLink.connect(backgroundScope, b, c)
        advanceTimeBy(10_000)
        assertEquals(2, b.device.entries.size)
        assertEquals(a.device.entries, b.device.entries)
        assertEquals(b.device.entries, c.device.entries)
    }

    @Test
    fun `a silent peer is dead after thirty seconds`() = runTest {
        val (a, b) = nodes(TestGroup(crypto, size = 2, random = random))
        var muted = false
        val (linkA, _) = SessionLink.connect(backgroundScope, a, b, bToA = StreamKnobs(drop = { muted }))
        advanceTimeBy(5_000)
        assertTrue(linkA.machine.isEstablished)
        muted = true
        advanceTimeBy(SyncSessionTimings.DEAD_AFTER_MS + 2 * SessionLink.TICK_MS)
        assertEquals(CloseReason.TIMEOUT, linkA.closeReason)
    }

    @Test
    fun `heartbeats keep a quiet session alive`() = runTest {
        val (a, b) = nodes(TestGroup(crypto, size = 2, random = random))
        val (linkA, linkB) = SessionLink.connect(backgroundScope, a, b)
        advanceTimeBy(120_000)
        assertNull(linkA.closeReason)
        assertNull(linkB.closeReason)
        assertTrue(linkA.sentTypes().count { it == FrameType.HEARTBEAT } >= 10)
    }

    @Test
    fun `heartbeats are empty and drive only liveness`() = runTest {
        val (a, b) = nodes(TestGroup(crypto, size = 2, random = random))
        a.create("one")
        val (linkA, linkB) = SessionLink.connect(backgroundScope, a, b)
        advanceTimeBy(5_000)
        assertTrue(linkB.machine.isSettled)
        val since = linkB.effects.size
        advanceTimeBy(5 * SyncSessionTimings.HEARTBEAT_INTERVAL_MS)
        val heartbeats = linkA.effects
            .filterIsInstance<SessionEffect.SendFrame>()
            .filter { it.frame.type == FrameType.HEARTBEAT }
        assertTrue(heartbeats.size >= 4)
        heartbeats.forEach { heartbeat ->
            val message = assertIs<ReassemblyResult.Complete>(Reassembler().accept(heartbeat.frame)).message
            assertEquals(0, message.payload.size)
        }
        // The receiver of a heartbeat does nothing but send its own.
        val later = linkB.effects.drop(since)
        assertEquals(listOf(FrameType.HEARTBEAT), later.map { (it as SessionEffect.SendFrame).frame.type }.distinct())
        assertTrue(linkB.machine.isSettled)

        // Not even a heartbeat with bytes in it asks for anything.
        val group = TestGroup(crypto, size = 2, random = random)
        val machine = machine(group)
        machine.start(BASE_TIME, founderEnvelope(group))
        deliver(machine, FrameType.HELLO, hello(group))
        val unseen = HashPayload.serializer().encodePayload(HashPayload("unseen"))
        val answers = (0..SyncSessionTimings.MAX_RESEND_REQUESTS).flatMap {
            deliver(machine, FrameType.HEARTBEAT, unseen)
        }
        assertEquals(emptyList(), answers)
    }

    @Test
    fun `a frame that does not fit the framing ends the session`() {
        val group = TestGroup(crypto, size = 2, random = random)
        val machine = machine(group)
        machine.start(BASE_TIME, founderEnvelope(group))
        deliver(machine, FrameType.HELLO, hello(group))
        // A whole message that is not padded.
        val unpadded = Frame(FrameType.ENVELOPE.code, FrameCodec.FLAG_FIN, nextMsgId++, ByteArray(10) { 1 })
        val effects = machine.onFrame(FrameCodec.encode(unpadded))
        assertEquals(SessionEffect.Close(CloseReason.PROTOCOL_ERROR), effects.last())
        assertTrue(machine.isClosed)
    }

    @Test
    fun `the roster goes before the envelope and is adopted before its entries`() = runTest {
        val group = TestGroup(crypto, size = 3, random = random)
        val (a, b, c) = nodes(group)
        // A holds an entry of C; B has not heard of C yet, so only the roster of A names it.
        val id = c.create("from c")
        a.device.receive(c.device)
        b.device.roster = group.roster.copy(devices = group.roster.devices.filter { it.id != c.device.id })
        val (linkA, linkB) = SessionLink.connect(backgroundScope, a, b)
        advanceTimeBy(5_000)

        val sent = linkA.sentTypes()
        assertTrue(sent.indexOf(FrameType.ROSTER) in 0 until sent.indexOf(FrameType.ENVELOPE))
        val applied = linkB.effects.filter { it is SessionEffect.ApplyRoster || it is SessionEffect.ApplyEnvelope }
        assertIs<SessionEffect.ApplyRoster>(applied.first())
        val envelope = assertIs<SessionEffect.ApplyEnvelope>(applied.single { it is SessionEffect.ApplyEnvelope })
        assertEquals(linkA.machine.localHash, envelope.hash)
        // Taken: the writer was known by the time the entries were checked.
        assertNotNull(b.device.entry(id))
        assertEquals(a.device.entries, b.device.entries)
    }

    @Test
    fun `no envelope of the peer is applied after a roster the session can not merge until it merges one`() {
        val group = TestGroup(crypto, size = 2, random = random)
        val machine = machine(group)
        machine.start(BASE_TIME, founderEnvelope(group))
        deliver(machine, FrameType.HELLO, hello(group))
        assertTrue(machine.isEstablished)
        // The roster of the peer, merged, would hold more devices than a roster may.
        val newcomers = List(Roster.MAX_DEVICES - 1) { index ->
            group.admit(group.newKeys(random), by = group.founder, at = Hlc(2_000L + index, 0, group.id(0)))
        }
        val full = group.roster.copy(devices = group.roster.devices + newcomers)
        val envelope = EnvelopeCodec.encode(Envelope(entries = emptyList()))

        deliver(machine, FrameType.ROSTER, Roster.serializer().encodePayload(full))
        val refused = deliver(machine, FrameType.ENVELOPE, envelope)
        assertTrue(refused.any { it is SessionEffect.SendFrame && it.frame.type == FrameType.ENVELOPE_ACK })
        assertTrue(refused.none { it is SessionEffect.ApplyEnvelope })
        // Nor any later envelope that comes without a roster.
        assertTrue(deliver(machine, FrameType.ENVELOPE, envelope).none { it is SessionEffect.ApplyEnvelope })

        deliver(machine, FrameType.ROSTER, Roster.serializer().encodePayload(group.roster))
        assertTrue(deliver(machine, FrameType.ENVELOPE, envelope).any { it is SessionEffect.ApplyEnvelope })
        assertFalse(machine.isClosed)
    }

    @Test
    fun `a roster that changes nothing is not handed to the host unless it asked for it again`() {
        val group = TestGroup(crypto, size = 3, random = random)
        val machine = machine(group)
        machine.start(BASE_TIME, founderEnvelope(group))
        deliver(machine, FrameType.HELLO, hello(group))
        assertTrue(machine.isEstablished)
        fun roster(roster: Roster) = deliver(machine, FrameType.ROSTER, Roster.serializer().encodePayload(roster))
            .filterIsInstance<SessionEffect.ApplyRoster>()

        // A peer may send the same roster, or none of its records, again and again.
        repeat(3) {
            assertEquals(emptyList(), roster(Roster(founder = group.founder.id)))
            assertEquals(emptyList(), roster(group.roster))
        }
        val removed = group.revoke(group.roster, group.keys[2], group.founder, Hlc(5_000, 0, group.founder.id))
        assertEquals(removed.revoked, roster(removed).single().roster.revoked)
        assertEquals(emptyList(), roster(removed))
        // The host may have dropped what it got: the roster the peer sends again is handed over again.
        machine.requestPeerResend()
        assertEquals(removed.revoked, roster(removed).single().roster.revoked)
        assertEquals(emptyList(), roster(removed))
        assertFalse(machine.isClosed)
    }

    @Test
    fun `the host asks for the roster and envelope again within the budget`() = runTest {
        val (a, b) = nodes(TestGroup(crypto, size = 2, random = random))
        a.create("one")
        val (linkA, linkB) = SessionLink.connect(backgroundScope, a, b)
        advanceTimeBy(5_000)
        assertTrue(linkB.machine.isSettled)
        fun envelopes() = linkA.sentTypes().count { it == FrameType.ENVELOPE }
        fun rosters() = linkA.sentTypes().count { it == FrameType.ROSTER }
        fun applied() = linkB.effects.filterIsInstance<SessionEffect.ApplyEnvelope>()
        val envelopesBefore = envelopes()
        val rostersBefore = rosters()
        val appliedBefore = applied().size

        // Each request brings the roster and the envelope once more, acknowledged or not.
        linkB.handle(linkB.machine.requestPeerResend())
        advanceTimeBy(1_000)
        assertEquals(envelopesBefore + 1, envelopes())
        assertEquals(rostersBefore + 1, rosters())
        assertEquals(appliedBefore + 1, applied().size)
        assertEquals(linkA.machine.localHash, applied().last().hash)

        // Never more than the budget, however often the host asks.
        repeat(SyncSessionTimings.MAX_RESEND_REQUESTS + 2) {
            linkB.handle(linkB.machine.requestPeerResend())
            advanceTimeBy(1_000)
        }
        val budget = SyncSessionTimings.MAX_RESEND_REQUESTS
        assertEquals(budget, linkB.sentTypes().count { it == FrameType.RESEND_REQUEST })
        assertEquals(envelopesBefore + budget, envelopes())
        assertTrue(linkB.machine.isSettled)
        assertNull(linkA.closeReason)
    }

    @Test
    fun `a peer gets as many resends as it may ask for`() {
        val group = TestGroup(crypto, size = 2, random = random)
        val machine = machine(group)
        machine.start(BASE_TIME, founderEnvelope(group))
        deliver(machine, FrameType.HELLO, hello(group))
        assertTrue(machine.isEstablished)
        val answers = List(SyncSessionTimings.MAX_RESEND_REQUESTS + 5) {
            deliver(machine, FrameType.RESEND_REQUEST, ByteArray(0))
        }
        assertEquals(
            SyncSessionTimings.MAX_RESEND_REQUESTS,
            answers.count { SessionEffect.RequestResend in it },
        )
    }

    @Test
    fun `an envelope with entries this build skips is acknowledged by the hash it was sent with`() {
        val group = TestGroup(crypto, size = 2, random = random)
        val machine = machine(group)
        machine.start(BASE_TIME, founderEnvelope(group))
        deliver(machine, FrameType.HELLO, hello(group))
        assertTrue(machine.isEstablished)

        // The peer sends an entry this build can not read.
        val future = buildJsonObject {
            put("id", random.nextUuid())
            put("kind", "card-reader")
            put("versions", "not a list")
        }
        val json = P2pJson.encodeToJsonElement(Envelope.serializer(), Envelope(entries = emptyList())).jsonObject
        val patched = JsonObject(json + ("entries" to JsonArray(listOf(future))))
        val bytes = CanonicalJson.encodeToByteArray(patched)
        val effects = deliver(machine, FrameType.ENVELOPE, bytes)
        val hash = P2pEncoding.hex(crypto.sha256(bytes))
        val ack = effects.filterIsInstance<SessionEffect.SendFrame>().single { it.frame.type == FrameType.ENVELOPE_ACK }
        val ackPayload = (Reassembler().accept(ack.frame) as ReassemblyResult.Complete).message.payload
        assertEquals(hash, HashPayload.serializer().decodePayloadOrNull(ackPayload)?.h)
        val applied = effects.filterIsInstance<SessionEffect.ApplyEnvelope>().single()
        assertEquals(hash, applied.hash)
        assertTrue(applied.envelope.entries.isEmpty())
    }

    @Test
    fun `an envelope that is not one ends the session`() {
        val group = TestGroup(crypto, size = 2, random = random)
        val machine = machine(group)
        machine.start(BASE_TIME, founderEnvelope(group))
        deliver(machine, FrameType.HELLO, hello(group))
        // The stream lost nothing, so asking again would bring the same bytes.
        val effects = deliver(machine, FrameType.ENVELOPE, "not json".encodeToByteArray())
        assertEquals(SessionEffect.Close(CloseReason.PROTOCOL_ERROR), effects.last())
        assertTrue(effects.none { it is SessionEffect.SendFrame && it.frame.type == FrameType.RESEND_REQUEST })
    }

    @Test
    fun `a local state too large to send is reported once and the peer is told`() = runTest {
        val (a, b) = nodes(TestGroup(crypto, size = 2, random = random))
        repeat(20) { a.create("item $it") }
        b.create("from b")
        // Between the two: only the state of A is too large.
        val limit = (a.envelopeSize() + b.envelopeSize()) / 2
        val (linkA, linkB) = SessionLink.connect(backgroundScope, a, b, maxEnvelopeSize = limit)
        advanceTimeBy(5_000)
        assertTrue(linkA.machine.isEstablished && linkB.machine.isEstablished)
        assertTrue(FrameType.ENVELOPE !in linkA.sentTypes())
        assertTrue(a.device.entries.keys.containsAll(b.device.entries.keys))
        val size = assertNotNull(linkA.machine.localEnvelopeTooLarge)
        assertTrue(size > limit)
        val reports = linkA.sentTypes().count { it == FrameType.ENVELOPE_TOO_LARGE }
        assertTrue(reports > 0)
        assertTrue(linkB.machine.isPeerEnvelopeTooLarge)
        // Neither side waits for more: all that can cross did.
        assertTrue(linkA.machine.isExchangeDone && linkB.machine.isExchangeDone)
        assertFalse(linkA.machine.isSettled || linkB.machine.isSettled)

        // Reported once per state: time neither repeats nor retries it.
        advanceTimeBy(10 * SyncSessionTimings.HEARTBEAT_INTERVAL_MS)
        assertEquals(reports, linkA.sentTypes().count { it == FrameType.ENVELOPE_TOO_LARGE })
        assertTrue(FrameType.RESEND_REQUEST !in linkB.sentTypes())
        assertNull(linkA.closeReason)
        assertNull(linkB.closeReason)
    }

    @Test
    fun `a merge that grows both states past the limit is reported on both sides`() = runTest {
        val (a, b) = nodes(TestGroup(crypto, size = 2, random = random))
        repeat(10) { a.create("from a $it") }
        repeat(10) { b.create("from b $it") }
        // Each state fits on its own; the merged one does not.
        val limit = maxOf(a.envelopeSize(), b.envelopeSize()) + 1
        val (linkA, linkB) = SessionLink.connect(backgroundScope, a, b, maxEnvelopeSize = limit)
        advanceTimeBy(5_000)
        assertEquals(20, a.device.entries.size)
        assertEquals(a.device.entries, b.device.entries)
        listOf(linkA, linkB).forEach { link ->
            val size = assertNotNull(link.machine.localEnvelopeTooLarge)
            assertTrue(size > limit)
            assertTrue(FrameType.ENVELOPE_TOO_LARGE in link.sentTypes())
            assertNull(link.closeReason)
        }
    }

    @Test
    fun `an envelope of the peer too large to take is dropped without asking again`() {
        val group = TestGroup(crypto, size = 2, random = random)
        val fitting = Envelope(entries = emptyList())
        val limit = EnvelopeCodec.encode(fitting).size + 100
        val machine = machine(group, maxEnvelopeSize = limit)
        machine.start(BASE_TIME, founderEnvelope(group))
        deliver(machine, FrameType.HELLO, hello(group))
        assertTrue(machine.isEstablished)

        val tooLarge = ByteArray(limit + 1) { 'x'.code.toByte() }
        assertEquals(emptyList(), deliver(machine, FrameType.ENVELOPE, tooLarge))
        assertTrue(machine.isPeerEnvelopeTooLarge)

        // The next envelope that fits is taken.
        val effects = deliver(machine, FrameType.ENVELOPE, EnvelopeCodec.encode(fitting))
        assertTrue(effects.any { it is SessionEffect.ApplyEnvelope })
        assertFalse(machine.isPeerEnvelopeTooLarge)
    }

    @Test
    fun `an envelope of the peer past the node limit is dropped without being parsed`() {
        val group = TestGroup(crypto, size = 2, random = random)
        val machine = machine(group)
        machine.start(BASE_TIME, founderEnvelope(group))
        deliver(machine, FrameType.HELLO, hello(group))
        assertTrue(machine.isEstablished)

        // Within the size limit, but a few KiB of gzip that would parse to a huge tree.
        val bomb = """{"v":1,"x":[""" + List(EnvelopeCodec.MAX_NODES) { "0" }.joinToString(",") + "]}"
        assertEquals(emptyList(), deliver(machine, FrameType.ENVELOPE, bomb.encodeToByteArray()))
        assertTrue(machine.isPeerEnvelopeTooLarge)
    }

    @Test
    fun `a local state past the node limit is reported not sent`() {
        val group = TestGroup(crypto, size = 2, random = random)
        val stamp = Hlc(BASE_TIME, 0, group.id(0))
        val dense = SyncEntry(
            id = random.nextUuid(),
            kind = EntryKind.WORDLIST,
            versions = listOf(
                EntryVersion(
                    stamp = stamp,
                    history = VersionVector(mapOf(stamp.node to stamp)),
                    body = JsonObject(mapOf("name" to JsonArray(List(EnvelopeCodec.MAX_NODES) { JsonPrimitive(0) }))),
                ),
            ),
        )
        val local = LocalEnvelope(group.roster, Envelope(entries = listOf(dense)), crypto::sha256)
        assertTrue(local.bytes.size < SyncSessionConfig.MAX_ENVELOPE_SIZE)
        val machine = machine(group)
        machine.start(BASE_TIME, local)

        val effects = deliver(machine, FrameType.HELLO, hello(group))
        assertTrue(machine.isEstablished)
        assertTrue(effects.none { it is SessionEffect.SendFrame && it.frame.type == FrameType.ENVELOPE })
        assertNotNull(machine.localEnvelopeTooLarge)
        assertTrue(effects.any { it is SessionEffect.SendFrame && it.frame.type == FrameType.ENVELOPE_TOO_LARGE })
    }

    @Test
    fun `a blob wanted before the first tick gets the whole timeout`() = runTest {
        val (a, b) = nodes(TestGroup(crypto, size = 2, random = random))
        val (linkA, _) = SessionLink.connect(backgroundScope, a, b)
        // Established before the first tick; the peer never answers, as SessionLink reads no blobs.
        advanceTimeBy(SessionLink.TICK_MS / 2)
        assertTrue(linkA.machine.isEstablished)
        linkA.handle(linkA.machine.blobs.want("missing"))
        advanceTimeBy(BlobExchangeConfig.TIMEOUT_MS)
        assertEquals(emptyList(), linkA.effects.filterIsInstance<SessionEffect.BlobFailed>())
        advanceTimeBy(2 * SessionLink.TICK_MS)
        assertEquals(
            listOf(SessionEffect.BlobFailed("missing", BlobFailure.TIMEOUT)),
            linkA.effects.filterIsInstance<SessionEffect.BlobFailed>(),
        )
    }

    @Test
    fun `a settled session sends an envelope only once`() = runTest {
        val (a, b) = nodes(TestGroup(crypto, size = 2, random = random))
        a.create("item")
        val (linkA, _) = SessionLink.connect(backgroundScope, a, b)
        advanceTimeBy(5_000)
        assertEquals(1, linkA.sentTypes().count { it == FrameType.ENVELOPE })
        advanceTimeBy(15L * 60L * 1_000L)
        assertEquals(1, linkA.sentTypes().count { it == FrameType.ENVELOPE })
        assertTrue(SessionEffect.RequestResend !in linkA.effects)
    }

    @Test
    fun `a revoked peer is cut off at once`() = runTest {
        val group = TestGroup(crypto, size = 3, random = random)
        val (a, b, c) = nodes(group)
        val (aToB, bToA) = SessionLink.connect(backgroundScope, a, b)
        SessionLink.connect(backgroundScope, a, c)
        val (cToB, _) = SessionLink.connect(backgroundScope, c, b)
        advanceTimeBy(5_000)

        // The founder removes B; C learns it from the founder and cuts B off too.
        val at = Hlc(BASE_TIME + 5_000, 0, group.founder.id)
        a.device.roster = group.revoke(a.device.roster, group.keys[1], group.founder, at)
        a.broadcast()
        advanceTimeBy(3_000)
        assertEquals(CloseReason.PEER_REVOKED, aToB.closeReason)
        assertEquals(CloseReason.LOCAL_REVOKED, bToA.closeReason)
        // C cuts B off itself, unless B, told by the founder, hung up first.
        assertTrue(cToB.closeReason in setOf(CloseReason.PEER_REVOKED, CloseReason.LOCAL_REVOKED))
        assertTrue(cToB.machine.isClosed)
    }

    @Test
    fun `a device that removes itself tells its peer before it goes`() = runTest {
        val group = TestGroup(crypto, size = 2, random = random)
        val (a, b) = nodes(group)
        val (aToB, bToA) = SessionLink.connect(backgroundScope, a, b)
        advanceTimeBy(5_000)

        // B leaves the group: it revokes itself and hands the roster to its session.
        val leaver = group.keys[1]
        val at = Hlc(BASE_TIME + 5_000, 0, leaver.id)
        b.device.roster = group.revoke(b.device.roster, leaver, leaver, at)
        b.broadcast()
        advanceTimeBy(3_000)
        assertEquals(CloseReason.LOCAL_REVOKED, bToA.closeReason)
        assertTrue(FrameType.ROSTER in bToA.sentTypes())
        assertEquals(CloseReason.PEER_REVOKED, aToB.closeReason)
        assertEquals(listOf(leaver.id), a.device.roster.revoked.map { it.deviceId })
    }

    @Test
    fun `a peer revoked while it was away learns it as the next session starts`() = runTest {
        val group = TestGroup(crypto, size = 2, random = random)
        val (a, b) = nodes(group)
        // The founder removed B while they were apart.
        val at = Hlc(BASE_TIME, 0, group.founder.id)
        a.device.roster = group.revoke(a.device.roster, group.keys[1], group.founder, at)
        val (aToB, bToA) = SessionLink.connect(backgroundScope, a, b)
        advanceTimeBy(3_000)
        assertEquals(listOf(FrameType.HELLO, FrameType.ROSTER, FrameType.BYE), aToB.sentTypes())
        assertEquals(CloseReason.PEER_REVOKED, aToB.closeReason)
        assertEquals(CloseReason.LOCAL_REVOKED, bToA.closeReason)
        assertEquals(a.device.roster.revoked, b.device.roster.revoked)
    }

    @Test
    fun `the reason of a BYE is reported as this device sees it`() {
        val group = TestGroup(crypto, size = 2, random = random)
        listOf(
            CloseReason.PROTOCOL_TOO_OLD to CloseReason.PROTOCOL_TOO_NEW,
            CloseReason.PROTOCOL_TOO_NEW to CloseReason.PROTOCOL_TOO_OLD,
            CloseReason.LOCAL_CLOSED to CloseReason.PEER_CLOSED,
            CloseReason.IDENTITY_MISMATCH to CloseReason.IDENTITY_MISMATCH,
            // Both devices connect again at once.
            CloseReason.RECONNECT to CloseReason.RECONNECT,
            // A peer alone can not make either device count as removed.
            CloseReason.PEER_REVOKED to CloseReason.PEER_CLOSED,
            CloseReason.LOCAL_REVOKED to CloseReason.PEER_CLOSED,
        ).forEach { (sent, seen) ->
            val machine = machine(group)
            machine.start(BASE_TIME, founderEnvelope(group))
            val bye = ByePayload.serializer().encodePayload(ByePayload(sent.name))
            val effects = deliver(machine, FrameType.BYE, bye)
            assertEquals(listOf(SessionEffect.Close(seen, remote = true)), effects, "for $sent")
        }
    }

    @Test
    fun `an envelope of another format ends the session with a reason to update`() {
        val group = TestGroup(crypto, size = 2, random = random)
        val machine = machine(group)
        machine.start(BASE_TIME, founderEnvelope(group))
        deliver(machine, FrameType.HELLO, hello(group))
        val newer = Envelope(v = Envelope.VERSION + 1, entries = emptyList())
        val effects = deliver(machine, FrameType.ENVELOPE, EnvelopeCodec.encode(newer))
        assertEquals(SessionEffect.Close(CloseReason.PROTOCOL_TOO_NEW), effects.last())
        val sent = effects.filterIsInstance<SessionEffect.SendFrame>().map { it.frame.type }
        assertEquals(listOf(FrameType.BYE), sent)
    }

    @Test
    fun `a blob window that arrives frame by frame over a slow link does not time out`() {
        val group = TestGroup(crypto, size = 2, random = random)
        val machine = machine(group)
        machine.start(BASE_TIME, founderEnvelope(group))
        deliver(machine, FrameType.HELLO, hello(group))
        machine.onTick(BASE_TIME)
        val bytes = Random(1).nextBytes(200_000)
        val hash = BlobExchange.hashOf(crypto, bytes)
        machine.blobs.want(hash)
        // One window of many frames; a frame arrives every 10 seconds.
        val window = BlobData(hash, offset = 0, total = bytes.size.toLong(), bytes = bytes).encode()
        val frames = FrameCodec.split(FrameType.BLOB_DATA, nextMsgId++, window)
        assertTrue(frames.size * 10_000L > BlobExchangeConfig.TIMEOUT_MS)
        val effects = frames.flatMapIndexed { index, frame ->
            machine.onFrame(FrameCodec.encode(frame)) + machine.onTick(BASE_TIME + (index + 1) * 10_000L)
        }
        assertEquals(emptyList(), effects.filterIsInstance<SessionEffect.BlobFailed>())
        assertContentEquals(bytes, effects.filterIsInstance<SessionEffect.PublishBlob>().single().bytes)
    }

    @Test
    fun `an old protocol version is refused with a typed reason`() {
        val group = TestGroup(crypto, size = 2, random = random)
        listOf(
            hello(group, v = 0, min = 0) to CloseReason.PROTOCOL_TOO_OLD,
            hello(group, v = 2, min = 2) to CloseReason.PROTOCOL_TOO_NEW,
            hello(group, groupId = "other") to CloseReason.GROUP_MISMATCH,
            hello(group, device = group.id(0)) to CloseReason.UNKNOWN_PEER,
        ).forEach { (payload, reason) ->
            val machine = machine(group)
            machine.start(BASE_TIME, founderEnvelope(group))
            val effects = deliver(machine, FrameType.HELLO, payload)
            assertEquals(SessionEffect.Close(reason), effects.last(), "for $reason")
            assertEquals(FrameType.BYE, (effects.first() as SessionEffect.SendFrame).frame.type)
            assertTrue(machine.isClosed)
        }
    }

    @Test
    fun `a peer with another static key is refused`() {
        val group = TestGroup(crypto, size = 2, random = random)
        val machine = machine(group, peerNoisePub = group.keys[0].noisePublic)
        machine.start(BASE_TIME, founderEnvelope(group))
        val effects = deliver(machine, FrameType.HELLO, hello(group))
        assertEquals(SessionEffect.Close(CloseReason.IDENTITY_MISMATCH), effects.last())
    }

    private var nextMsgId = 1_000_000

    /** Hands [payload] to [machine] as a message of the peer. */
    private fun deliver(machine: SyncSessionStateMachine, type: FrameType, payload: ByteArray): List<SessionEffect> =
        FrameCodec.split(type, nextMsgId++, payload).flatMap { machine.onFrame(FrameCodec.encode(it)) }

    /** The envelope of the founder, as the sessions send it. */
    private fun founderEnvelope(group: TestGroup) = SimDevice(group.id(0), group) { BASE_TIME }.localEnvelope()

    private fun SessionNode.envelopeSize(): Int = EnvelopeCodec.encode(device.envelope()).size

    private fun machine(
        group: TestGroup,
        peerNoisePub: String = group.keys[1].noisePublic,
        maxEnvelopeSize: Int = SyncSessionConfig.MAX_ENVELOPE_SIZE,
    ) = SyncSessionStateMachine(
        config = SyncSessionConfig(
            localDeviceId = group.id(0),
            peerDeviceId = group.id(1),
            peerNoisePub = peerNoisePub,
            groupId = group.groupId,
            maxEnvelopeSize = maxEnvelopeSize,
        ),
        roster = group.roster,
        crypto = crypto,
        compressor = IdentityCompressor,
    )

    private fun hello(
        group: TestGroup,
        v: Int = ProtocolVersion.CURRENT,
        min: Int = ProtocolVersion.MIN_SUPPORTED,
        groupId: String = group.groupId,
        device: String = group.id(1),
    ): ByteArray = HelloPayload.serializer().encodePayload(
        HelloPayload(v = v, min = min, device = device, group = groupId, roster = "", envelope = null),
    )

    private companion object {
        const val BASE_TIME = 1_800_000_000_000L
    }
}
