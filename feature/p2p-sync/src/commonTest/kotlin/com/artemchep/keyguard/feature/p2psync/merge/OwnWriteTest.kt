package com.artemchep.keyguard.feature.p2psync.merge

import com.artemchep.keyguard.feature.p2psync.P2pJson
import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.clock.HlcClock
import com.artemchep.keyguard.feature.p2psync.clock.VersionVector
import com.artemchep.keyguard.feature.p2psync.model.CanonicalJson
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.EntryVersion
import com.artemchep.keyguard.feature.p2psync.model.RejectReason
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.testing.ForwarderBodies
import com.artemchep.keyguard.feature.p2psync.testing.TestCrypto
import com.artemchep.keyguard.feature.p2psync.testing.TestGroup
import com.artemchep.keyguard.feature.p2psync.testing.nextUuid
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** A device never publishes a version or delete that its peers would drop. */
class OwnWriteTest {
    private val random = Random(43)
    private val group = TestGroup(TestCrypto(43), size = 2, random = random)
    private val keys = group.keys[1]
    private val now = 20_000_000L
    private val clock = HlcClock(node = keys.id, physicalNow = { now }, persist = {})
    private val signer = group.entrySigner(keys)
    private val publisher = LocalEditPublisher()
    private val kind = EntryKind.EMAIL_FORWARDER

    private fun publish(local: JsonObject, current: SyncEntry? = null): SyncEntry = publisher.publish(
        id = current?.id ?: random.nextUuid(),
        kind = kind,
        current = current,
        applied = current?.body,
        local = local,
        clock = clock,
        signer = signer,
        budget = budget(),
    )

    private fun budget() = EntryBudget.of(emptyList())

    private fun refusal(block: () -> Unit): RejectReason = assertFailsWith<EditRefusedException> { block() }.reason

    /**
     * An entry whose version, by another device, builds on the edits of [writers] devices but
     * this one, with a stamp of [wall].
     */
    private fun entryOf(writers: Int, wall: Long = now - 1): SyncEntry {
        val id = random.nextUuid()
        val stamp = Hlc(wall, 0, "w0")
        val others = (1 until writers).map { Hlc(wall - 1, 0, "w$it") }
        val history = VersionVector.of(others + stamp)
        val parts = (kind.schema.parts.map { it.key } + EntryVersion.REMAINDER).associateWith { stamp }
        val version = signer.version(id, kind, stamp, history, ForwarderBodies.forwarder("x"), parts)
        return SyncEntry(id = id, kind = kind, versions = listOf(version))
    }

    @Test
    fun `a body larger than the largest one is not published`() {
        val overhead = CanonicalJson.encodeToByteArray(ForwarderBodies.forwarder("")).size
        val name = "x".repeat(SanitizerLimits.MAX_BODY_BYTES - overhead + 1)
        assertEquals(RejectReason.OVERSIZED_BODY, refusal { publish(ForwarderBodies.forwarder(name)) })
        // One byte less is published, and peers take it.
        val entry = publish(ForwarderBodies.forwarder(name.drop(1)))
        assertNull(Sanitizer(group.roster, group.groupId, group.crypto, now).check(entry).reason)
    }

    @Test
    fun `a body with a number without a canonical form is not published`() {
        val body = JsonObject(ForwarderBodies.forwarder("x") + ("count" to P2pJson.parseToJsonElement("1.5")))
        assertEquals(RejectReason.MALFORMED, refusal { publish(body) })
    }

    @Test
    fun `an edit or a delete that builds on more writers than a roster holds is not published`() {
        val current = entryOf(writers = SanitizerLimits.MAX_HISTORY_WRITERS)
        assertEquals(RejectReason.TOO_MANY_WRITERS, refusal { publish(ForwarderBodies.forwarder("y"), current) })
        assertEquals(RejectReason.TOO_MANY_WRITERS, refusal { publisher.tombstone(current, clock, signer, budget()) })
        // One writer less fits.
        val fits = entryOf(writers = SanitizerLimits.MAX_HISTORY_WRITERS - 1)
        assertEquals(SanitizerLimits.MAX_HISTORY_WRITERS, publish(ForwarderBodies.forwarder("y"), fits).vv.stamps.size)
    }

    @Test
    fun `an edit stamped too far in the future is not published`() {
        // The entry came from a device whose clock ran ahead, as far as peers allow.
        val current = entryOf(writers = 1, wall = now + HlcClock.MAX_FUTURE_DRIFT_MS)
        val later = HlcClock(node = keys.id, physicalNow = { now - 1 }, persist = {})
        val reason = refusal {
            publisher.publish(
                current.id,
                kind,
                current,
                current.body,
                ForwarderBodies.forwarder("y"),
                later,
                signer,
                budget(),
            )
        }
        assertEquals(RejectReason.FUTURE_STAMP, reason)
    }
}
