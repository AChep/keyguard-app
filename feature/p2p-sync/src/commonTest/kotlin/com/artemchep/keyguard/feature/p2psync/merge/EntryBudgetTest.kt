package com.artemchep.keyguard.feature.p2psync.merge

import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.clock.HlcClock
import com.artemchep.keyguard.feature.p2psync.clock.VersionVector
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.EntryVersion
import com.artemchep.keyguard.feature.p2psync.model.Envelope
import com.artemchep.keyguard.feature.p2psync.model.EnvelopeCodec
import com.artemchep.keyguard.feature.p2psync.model.RejectReason
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.session.SyncSessionConfig
import com.artemchep.keyguard.feature.p2psync.testing.ForwarderBodies
import com.artemchep.keyguard.feature.p2psync.testing.TestCrypto
import com.artemchep.keyguard.feature.p2psync.testing.TestGroup
import com.artemchep.keyguard.feature.p2psync.testing.nextUuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The entries of a group never outgrow what one envelope carries, whoever sends them. */
class EntryBudgetTest {
    private val random = Random(41)
    private val group = TestGroup(TestCrypto(41), size = 2, random = random)
    private val now = 50_000_000L
    private val writer = group.id(1)
    private val signer = group.entrySigner(group.keys[1])
    private val kind = EntryKind.EMAIL_FORWARDER
    private val keys = kind.schema.parts.map { it.key } + EntryVersion.REMAINDER
    private val merger = EnvelopeMerger()

    private fun sanitizer() = Sanitizer(group.roster, group.groupId, group.crypto, now)

    /** A new entry by the [writer] whose body takes about [size] bytes. */
    private fun entry(size: Int): SyncEntry {
        val id = random.nextUuid()
        val stamp = Hlc(now, 0, writer)
        val body = padded("big", size)
        val history = VersionVector.of(listOf(stamp))
        val version = signer.version(id, kind, stamp, history, body, keys.associateWith { stamp })
        return SyncEntry(id = id, kind = kind, versions = listOf(version))
    }

    /** A forwarder [name] whose body takes about [size] bytes more. */
    private fun padded(name: String, size: Int): JsonObject =
        JsonObject(ForwarderBodies.forwarder(name) + ("padding" to JsonPrimitive("x".repeat(size))))

    private fun Collection<SyncEntry>.envelopeSize(): Int = EnvelopeCodec.encode(Envelope(entries = toList())).size

    @Test
    fun `envelopes that each fit never merge into state that does not`() {
        val first = List(6) { entry(size = 200 * 1024) }
        val second = List(6) { entry(size = 200 * 1024) }
        val one = merger.merge(emptyMap(), Envelope(entries = first), writer, sanitizer())
        assertEquals(first.size, one.accepted.size)

        val two = merger.merge(one.entries, Envelope(entries = second), writer, sanitizer())
        assertTrue(two.rejected.isNotEmpty())
        assertTrue(two.rejected.all { it.reason == RejectReason.OVER_BUDGET })
        val size = two.entries.values.envelopeSize()
        assertTrue(size <= SyncSessionConfig.MAX_ENVELOPE_SIZE, "The envelope takes $size bytes")

        // A delete takes less than the version it replaces, so it fits a full budget.
        val victim = first.first()
        val at = Hlc(now + 1, 0, writer)
        val delete = signer.delete(victim.id, victim.kind, at, victim.vv.witness(at))
        val three = merger.merge(
            two.entries,
            Envelope(entries = listOf(victim.copy(versions = emptyList(), deletes = listOf(delete)))),
            writer,
            sanitizer(),
        )
        assertEquals(1, three.accepted.size)
        assertTrue(three.entries.getValue(victim.key).deleted)
    }

    @Test
    fun `a local edit past the budget stays on this device`() {
        val held = List(9) { entry(size = 200 * 1024) }
        val clock = HlcClock(node = writer, physicalNow = { now }, persist = {})
        val refusal = assertFailsWith<EditRefusedException> {
            LocalEditPublisher().publish(
                id = random.nextUuid(),
                kind = kind,
                current = null,
                applied = null,
                local = padded("new", size = 200 * 1024),
                clock = clock,
                signer = signer,
                budget = EntryBudget.of(held),
            )
        }
        assertEquals(RejectReason.OVER_BUDGET, refusal.reason)
    }
}
