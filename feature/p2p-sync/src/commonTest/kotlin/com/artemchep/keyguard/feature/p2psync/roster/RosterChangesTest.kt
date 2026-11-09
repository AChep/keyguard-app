package com.artemchep.keyguard.feature.p2psync.roster

import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.testing.TestCrypto
import com.artemchep.keyguard.feature.p2psync.testing.TestGroup
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RosterChangesTest {
    private val group = TestGroup(TestCrypto(23), size = 3, random = Random(23))
    private val founder = group.keys[0]
    private val second = group.keys[1]
    private val third = group.keys[2]

    private fun at(wall: Long, node: String = founder.id) = Hlc(wall, 0, node)

    private fun changes(before: Roster, after: Roster, selfId: String) =
        rosterChanges(before, after, selfId, group.crypto::sha256)

    @Test
    fun `an added device is reported with the device that added it`() {
        val newcomer = group.newKeys(Random(1))
        val admitted = group.admit(newcomer, second, at(5_000, second.id))
        val after = group.roster.copy(devices = group.roster.devices + admitted)
        assertEquals(
            listOf(RosterChange.Added(newcomer.id, by = second.id)),
            changes(group.roster, after, selfId = third.id),
        )
    }

    @Test
    fun `a removed device is reported with the device that removed it, or itself if it left`() {
        val removed = group.revoke(group.roster, second, founder, at(5_000))
        assertEquals(
            listOf(RosterChange.Removed(second.id, by = founder.id)),
            changes(group.roster, removed, selfId = third.id),
        )
        val left = group.revoke(group.roster, third, third, at(5_000, third.id))
        assertEquals(
            listOf(RosterChange.Removed(third.id, by = third.id)),
            changes(group.roster, left, selfId = second.id),
        )
    }

    @Test
    fun `a device that goes with its inviter has no remover`() {
        val child = group.newKeys(Random(2))
        val admitted = group.admit(child, second, at(5_000, second.id))
        val before = group.roster.copy(devices = group.roster.devices + admitted)
        // The founder removes the second device and leaves out the one it added.
        val revocation = group.signer.revoke(before, second.id, at(6_000), founder.id, founder.authorityPrivate) {
            it.id != child.id
        }
        val after = before.copy(revoked = before.revoked + revocation)
        assertEquals(
            listOf(RosterChange.Removed(child.id, by = null), RosterChange.Removed(second.id, by = founder.id))
                .sortedBy { it.deviceId },
            changes(before, after, selfId = third.id),
        )
    }

    @Test
    fun `changes this device made are left out`() {
        val removed = group.revoke(group.roster, second, founder, at(5_000))
        assertEquals(emptyList(), changes(group.roster, removed, selfId = founder.id))
        assertEquals(emptyList(), changes(group.roster, group.roster, selfId = third.id))
    }

    @Test
    fun `a device removed by a device that was removed already is reported so`() {
        // The founder removes the second device, which still knows its master password and
        // then removes the third one.
        val removed = group.revoke(group.roster, second, founder, at(5_000))
        val after = group.revoke(removed, third, second, at(6_000, second.id))
        // The second device is not named: its label is its own to pick.
        assertEquals(
            listOf(RosterChange.Removed(third.id, by = null, byRemoved = true)),
            changes(removed, after, selfId = founder.id),
        )
        // Stamped before its own removal, it looks the same: a signer picks its stamps.
        val backdated = group.revoke(removed, third, second, at(4_000, second.id))
        assertTrue(group.activity(backdated).wasRevokedByRevoked(third.id))
        // The founder, which removed the second device, is active.
        assertFalse(group.activity(backdated).wasRevokedByRevoked(second.id))
    }

    @Test
    fun `a device removed by a device that a removed device added is reported so`() {
        val removed = group.revoke(group.roster, second, founder, at(5_000))
        // The removed second device adds one of its own, which is never active, to remove the third.
        val own = group.newKeys(Random(3))
        val minted = removed.copy(devices = removed.devices + group.admit(own, second, at(6_000, second.id)))
        val after = group.revoke(minted, third, own, at(6_001, own.id))
        assertEquals(
            listOf(RosterChange.Removed(third.id, by = null, byRemoved = true)),
            changes(minted, after, selfId = founder.id),
        )
    }

    @Test
    fun `an active device that removed a device is named, never a removed one`() {
        val removed = group.revoke(group.roster, second, founder, at(5_000))
        // The second device removes the third with the earliest stamp; the founder removes it too.
        val both = group.revoke(group.revoke(removed, third, second, at(1_000, second.id)), third, founder, at(6_000))
        assertFalse(group.activity(both).wasRevokedByRevoked(third.id))
        assertEquals(
            listOf(RosterChange.Removed(third.id, by = founder.id)),
            changes(removed, both, selfId = third.id),
        )
        // A device that left is not reported as removed by a removed device.
        val removedByRemoved = group.revoke(removed, third, second, at(6_000, second.id))
        val left = group.revoke(removedByRemoved, third, third, at(6_001, third.id))
        assertFalse(group.activity(left).wasRevokedByRevoked(third.id))
        assertEquals(
            listOf(RosterChange.Removed(third.id, by = third.id)),
            changes(removed, left, selfId = founder.id),
        )
    }
}
