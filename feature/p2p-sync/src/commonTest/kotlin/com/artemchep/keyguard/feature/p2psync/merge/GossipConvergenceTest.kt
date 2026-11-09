package com.artemchep.keyguard.feature.p2psync.merge

import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.testing.EditKind
import com.artemchep.keyguard.feature.p2psync.testing.ForwarderBodies
import com.artemchep.keyguard.feature.p2psync.testing.RandomEdits
import com.artemchep.keyguard.feature.p2psync.testing.SimDevice
import com.artemchep.keyguard.feature.p2psync.testing.TestCrypto
import com.artemchep.keyguard.feature.p2psync.testing.TestGroup
import com.artemchep.keyguard.feature.p2psync.testing.nextUuid
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Up to six devices make random edits while envelopes travel in random order between the
 * devices that can reach each other; partitions come and go. Once everyone has talked to
 * everyone, every device must hold the same entries, equal to merging every version ever
 * published in one go.
 */
class GossipConvergenceTest {
    private val merger = EntryMerger

    private class Simulation(seed: Int) {
        val random = Random(seed)
        val edits = RandomEdits(random)
        val size = 2 + random.nextInt(5)
        val group = TestGroup(TestCrypto(seed), size = size, random = Random(seed))
        var time = 1_000_000_000L
        private val skews = List(size) { random.nextLong(-60_000L, 60_000L) }
        val devices = group.keys.mapIndexed { index, keys -> SimDevice(keys.id, group) { time + skews[index] } }
        var islands: List<Int> = List(size) { 0 }

        fun reachable(a: Int, b: Int) = islands[a] == islands[b]

        fun step() {
            time += random.nextLong(1L, 3_000L)
            if (random.nextInt(25) == 0) islands = List(size) { random.nextInt(3) }
            val index = random.nextInt(size)
            if (random.nextInt(10) < 4) gossip(index) else operate(devices[index])
        }

        private fun gossip(index: Int) {
            val other = random.nextInt(size)
            if (other != index && reachable(index, other)) devices[other].receive(devices[index])
        }

        private fun operate(device: SimDevice) {
            val live = device.view.keys.sorted()
            val id = live.randomOrNull(random)
            when {
                id == null || random.nextInt(8) == 0 ->
                    device.edit(random.nextUuid(), ForwarderBodies.forwarder("forwarder"))
                random.nextInt(10) == 0 -> device.delete(id)
                else -> device.edit(id, edits.apply(device.view.getValue(id), EditKind.entries.random(random)))
            }
        }

        fun heal() {
            islands = List(size) { 0 }
            repeat(size + 1) {
                devices.forEach { from -> devices.forEach { to -> if (from !== to) to.receive(from) } }
            }
        }
    }

    @Test
    fun `devices converge to the sequential merge of every version`() {
        repeat(SEEDS) { seed ->
            val simulation = Simulation(seed)
            repeat(OPERATIONS) { simulation.step() }
            simulation.heal()

            val reference = simulation.devices.first().entries
            simulation.devices.forEach { device ->
                assertEquals(reference, device.entries, "seed=$seed: device ${device.id} diverged")
                val live = reference.values.filter { !it.deleted }.associate { it.id to it.body }
                assertEquals(live, device.view, "seed=$seed: the local database of ${device.id} is stale")
            }
            val published = simulation.devices.flatMap { it.published }.shuffled(simulation.random)
            val sequential = published.groupBy { it.key }.mapValues { (_, versions) -> versions.reduce(merger::merge) }
            assertEquals(sequential, reference, "seed=$seed: differs from the sequential merge")
        }
    }

    @Test
    fun `the simulation exercises concurrency and deletes`() {
        var deleted = 0
        var concurrent = 0
        repeat(SEEDS) { seed ->
            val simulation = Simulation(seed)
            repeat(OPERATIONS) { simulation.step() }
            simulation.heal()
            val entries: Collection<SyncEntry> = simulation.devices.first().entries.values
            deleted += entries.count { it.deleted }
            concurrent += entries.count { it.vv.stamps.size > 1 }
        }
        assertTrue(deleted > 0, "no entry was deleted")
        assertTrue(concurrent > 0, "no entry was edited on two devices")
    }

    @Test
    fun `devices agree on a write whatever they learn first of the revocation of its writer`() {
        val group = TestGroup(TestCrypto(41), size = 3, random = Random(41))
        var time = 1_000_000_000L
        val (founder, writer, other) = group.simDevices { time }
        // The founder removes the writer, which does not know yet and edits on.
        founder.revoke(group.keys[1])
        time += 1_000L
        writer.edit(Random(41).nextUuid(), ForwarderBodies.forwarder("after the revocation"))

        // The other device takes the write before it hears of the revocation, the founder
        // after: it can not take it back, so the founder takes it too.
        other.receive(writer)
        other.receive(founder)
        founder.receive(other)
        assertEquals(other.entries, founder.entries)
        assertEquals(1, founder.entries.size)
        // The founder takes nothing from the removed device itself.
        val direct = founder.receive(writer)
        assertTrue(direct.accepted.isEmpty())
    }

    private companion object {
        const val SEEDS = 20
        const val OPERATIONS = 200
    }
}
