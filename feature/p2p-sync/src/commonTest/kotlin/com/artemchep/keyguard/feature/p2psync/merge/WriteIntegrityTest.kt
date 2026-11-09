package com.artemchep.keyguard.feature.p2psync.merge

import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.clock.HlcClock
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.EntryVersion
import com.artemchep.keyguard.feature.p2psync.model.Envelope
import com.artemchep.keyguard.feature.p2psync.model.RejectReason
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.testing.ForwarderBodies
import com.artemchep.keyguard.feature.p2psync.testing.SimDevice
import com.artemchep.keyguard.feature.p2psync.testing.TestCrypto
import com.artemchep.keyguard.feature.p2psync.testing.TestGroup
import com.artemchep.keyguard.feature.p2psync.testing.schema
import com.artemchep.keyguard.feature.p2psync.testing.nextUuid
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a device that turns against the group can and can not do to the entries: it writes
 * only as itself, and never far ahead of the others.
 */
class WriteIntegrityTest {
    private val random = Random(17)
    private var time = 2_000_000_000L

    private fun devices(count: Int): List<SimDevice> =
        TestGroup(TestCrypto(17), size = count, random = random).simDevices { time }

    /** The envelope of this device with the [entries] in place of its own. */
    private fun SimDevice.envelopeOf(vararg entries: SyncEntry): Envelope = envelope().copy(entries = entries.toList())

    /** The entry [id] as this device holds it, plus a rename to [name] stamped [stamp] and signed by [signer]. */
    private fun SimDevice.renamed(id: String, name: String, stamp: Hlc, signer: EntrySigner = this.signer): SyncEntry {
        val current = entryOf(id)
        val keys = current.schema().parts.map { it.key } + EntryVersion.REMAINDER
        val version = signer.version(
            entryId = id,
            kind = EntryKind.EMAIL_FORWARDER,
            stamp = stamp,
            history = current.vv.witness(stamp),
            body = ForwarderBodies.rename(requireNotNull(current.body), name),
            parts = keys.associateWith { stamp },
        )
        return current.copy(versions = current.versions + version).normalized()
    }

    @Test
    fun `a write stamped ahead holds only until the next edit`() {
        val (a, m) = devices(2)
        val id = random.nextUuid()
        a.edit(id, ForwarderBodies.forwarder("item"))
        a.share(m)
        // M stamps its write as far ahead as the others accept.
        val ahead = Hlc(time + HlcClock.MAX_FUTURE_DRIFT_MS, 0, m.id)
        a.receive(m, m.envelopeOf(m.renamed(id, "from m", ahead)))
        assertEquals("from m", a.name(id))
        // A's clock witnessed the stamp: its next edit is newer, and wins at once.
        time += 1_000L
        a.edit(id, ForwarderBodies.rename(a.view.getValue(id), "from a"))
        assertTrue(a.entryOf(id).stamp > ahead)
        a.share(m)
        assertEquals("from a", m.name(id))
    }

    @Test
    fun `a write stamped too far ahead is dropped`() {
        val (a, m) = devices(2)
        val id = random.nextUuid()
        a.edit(id, ForwarderBodies.forwarder("item"))
        a.share(m)
        val ahead = Hlc(time + HlcClock.MAX_FUTURE_DRIFT_MS + 1, 0, m.id)
        val sent = m.envelopeOf(m.renamed(id, "from m", ahead))
        val result = a.receive(m, sent)
        assertEquals(RejectReason.FUTURE_STAMP, result.rejected.single().reason)
        assertEquals("item", a.name(id))
        // The same write is taken once the clock caught up with it.
        assertEquals(ahead.wall - HlcClock.MAX_FUTURE_DRIFT_MS, result.validFrom)
        time = assertNotNull(result.validFrom)
        val again = a.receive(m, sent)
        assertEquals(emptyList(), again.rejected)
        assertNull(again.validFrom)
        assertEquals("from m", a.name(id))
    }

    @Test
    fun `a device can not relay a write in the name of another`() {
        val (a, b, m) = devices(3)
        val id = random.nextUuid()
        a.edit(id, ForwarderBodies.forwarder("item"))
        a.share(b, m)
        // M signs a rename as if A made it, newer than anything A wrote.
        val forged = m.renamed(id, "from m", Hlc(time + 1, 0, a.id), signer = m.signer)
        val result = b.receive(m, m.envelopeOf(forged))
        assertEquals(RejectReason.FORGED, result.rejected.single().reason)
        assertEquals("item", b.name(id))
        assertEquals(a.entries, b.entries)
    }

    @Test
    fun `devices converge whatever they learn first of a removal`() {
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            val group = TestGroup(TestCrypto(seed), size = 4, random = Random(seed))
            val sims = group.simDevices { time }
            val (founder, removed) = sims
            val ids = List(4) { random.nextUuid() }
            repeat(40) { step ->
                time += random.nextLong(1, 2_000)
                val device = sims.random(random)
                val id = ids.random(random)
                val body = device.view[id]
                when {
                    step == 20 -> founder.revoke(removed.keys)
                    random.nextInt(3) == 0 -> sims.random(random).receive(device)
                    body == null -> device.edit(id, ForwarderBodies.forwarder("$step"))
                    random.nextInt(8) == 0 -> device.delete(id)
                    else -> device.edit(id, ForwarderBodies.rename(body, "$step"))
                }
            }
            repeat(sims.size + 1) {
                sims.forEach { from -> sims.forEach { to -> if (from !== to) to.receive(from) } }
            }
            (sims - removed).forEach { device ->
                assertEquals(founder.entries, device.entries, "seed=$seed: ${device.id} diverged")
                assertEquals(founder.view, device.view, "seed=$seed: ${device.id} is stale")
            }
        }
    }

    private companion object {
        const val SEEDS = 20
    }
}
