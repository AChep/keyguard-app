package com.artemchep.keyguard.feature.p2psync.merge

import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.testing.ForwarderBodies
import com.artemchep.keyguard.feature.p2psync.testing.RandomEdits
import com.artemchep.keyguard.feature.p2psync.testing.TestCrypto
import com.artemchep.keyguard.feature.p2psync.testing.TestGroup
import com.artemchep.keyguard.feature.p2psync.testing.nextUuid
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntryMergerLawsTest {
    private val merger = EntryMerger

    /**
     * Every version of one entry seen while [devices] devices edit, delete and exchange it
     * at random: the reachable states the merge must handle.
     */
    private fun versions(seed: Int, devices: Int = 4, steps: Int = 80): List<SyncEntry> {
        val random = Random(seed)
        val edits = RandomEdits(random)
        val group = TestGroup(TestCrypto(seed), size = devices, random = Random(seed))
        var time = 1_000_000L
        val sims = group.simDevices { time }
        val entryId = random.nextUuid()
        sims.first().edit(entryId, ForwarderBodies.forwarder("forwarder"))
        sims.drop(1).forEach { it.receive(sims.first()) }
        val seen = mutableListOf<SyncEntry>()
        repeat(steps) {
            time += random.nextLong(0, 2_000)
            val device = sims.random(random)
            val body = device.view[entryId]
            when {
                random.nextInt(4) == 0 -> device.receive(sims.random(random))
                body == null -> device.receive(sims.random(random))
                random.nextInt(12) == 0 -> device.delete(entryId)
                else -> device.edit(entryId, edits.apply(body))
            }
            device.entry(entryId)?.let { seen += it }
        }
        return seen.distinct()
    }

    @Test
    fun `merge is commutative associative and idempotent`() {
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            val versions = versions(seed)
            repeat(300) {
                val a = versions.random(random)
                val b = versions.random(random)
                val c = versions.random(random)
                assertEquals(merger.merge(a, b), merger.merge(b, a), "seed=$seed: commutative")
                assertEquals(
                    merger.merge(merger.merge(a, b), c),
                    merger.merge(a, merger.merge(b, c)),
                    "seed=$seed: associative",
                )
                assertEquals(a, merger.merge(a, a), "seed=$seed: idempotent")
            }
        }
    }

    @Test
    fun `the merge keeps what nothing replaces`() {
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            val versions = versions(seed)
            repeat(300) {
                val a = versions.random(random)
                val b = versions.random(random)
                val merged = merger.merge(a, b)
                assertEquals(a.vv.join(b.vv), merged.vv, "seed=$seed: vv")
                assertEquals(maxOf(a.stamp, b.stamp), merged.stamp, "seed=$seed: stamp")
                val versionHistories = (a.versions + b.versions).map { it.history }
                val histories = versionHistories + (a.deletes + b.deletes).map { it.history }
                val kept = merged.versions.map { it.history } + merged.deletes.map { it.history }
                kept.forEach { history ->
                    val replaced = histories.any { it != history && it.covers(history) }
                    assertFalse(replaced, "seed=$seed: kept a replaced version")
                }
                histories.filter { history -> histories.none { it != history && it.covers(history) } }
                    .forEach { assertTrue(it in kept, "seed=$seed: dropped a version nothing replaces") }
            }
        }
    }

    @Test
    fun `a single version shows its own body and a delete shows none`() {
        repeat(SEEDS) { seed ->
            versions(seed).forEach { entry ->
                when {
                    entry.versions.isEmpty() -> assertNull(entry.body, "seed=$seed")
                    entry.versions.size == 1 -> assertEquals(entry.versions.single().body, entry.body, "seed=$seed")
                }
            }
        }
    }

    @Test
    fun `merging the same versions in any order gives the same entry`() {
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            val versions = versions(seed)
            val expected = versions.reduce(merger::merge)
            repeat(20) {
                assertEquals(expected, versions.shuffled(random).reduce(merger::merge), "seed=$seed")
            }
        }
    }

    @Test
    fun `entries of different kinds are different entries and never merge`() {
        val entry = versions(seed = 1).last()
        val other = entry.copy(kind = EntryKind.entries.first { it != entry.knownKind }.wireName)
        assertFailsWith<IllegalArgumentException> { merger.merge(entry, other) }
    }

    private companion object {
        const val SEEDS = 25
    }
}
