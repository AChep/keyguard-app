package com.artemchep.keyguard.feature.p2psync.roster

import com.artemchep.keyguard.feature.p2psync.P2pJson
import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.RosterDevice
import com.artemchep.keyguard.feature.p2psync.testing.TestCrypto
import com.artemchep.keyguard.feature.p2psync.testing.TestDeviceKeys
import com.artemchep.keyguard.feature.p2psync.testing.TestGroup
import com.artemchep.keyguard.feature.p2psync.testing.nextUuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RosterCrdtTest {
    private val crypto = TestCrypto()
    private val group = TestGroup(crypto, size = 3)
    private val validator = group.validator
    private val founder = group.keys[0]
    private val second = group.keys[1]
    private val third = group.keys[2]

    private fun at(wall: Long, node: String = founder.id) = Hlc(wall, 0, node)

    /** Returns [device], the second device's record, renamed by the second device itself. */
    private fun rename(device: RosterDevice, label: String, wall: Long) =
        group.signer.update(device, at(wall, second.id), second.signPrivate, label = label)

    private fun record(roster: Roster, id: String) = roster.devices.single { it.id == id }

    @Test
    fun `a device renames itself and the newer record wins`() {
        val original = record(group.roster, second.id)
        val renamed = rename(original, "Work phone", 7_000)
        val newer = group.roster.copy(devices = group.roster.devices - original + renamed)
        // The admission covers the identity only, so it stays valid.
        assertEquals(original.admission, renamed.admission)
        assertEquals(newer.normalized(), validator.validate(newer).normalized())
        assertEquals(renamed, record(RosterCrdt.merge(group.roster, newer), second.id))
        assertEquals(renamed, record(RosterCrdt.merge(newer, group.roster), second.id))
        // A later rename wins over an earlier one, whatever the order of arrival.
        val renamedAgain = rename(renamed, "Old phone", 8_000)
        val newest = newer.copy(devices = newer.devices - renamed + renamedAgain)
        assertEquals(renamedAgain, record(RosterCrdt.merge(newer, newest), second.id))
        assertEquals(renamedAgain, record(RosterCrdt.merge(newest, newer), second.id))
        assertTrue(group.activity(validator.validate(newest)).isActive(second.id))
    }

    @Test
    fun `a renamed record must be signed by the device itself`() {
        val original = record(group.roster, second.id)
        val forged = group.signer.update(original, at(9_000, third.id), third.signPrivate, label = "Evil")
        val stale = original.copy(label = "Evil", updatedAt = at(9_000, second.id))
        listOf(forged, stale).forEach { bad ->
            val roster = group.roster.copy(devices = group.roster.devices - original + bad)
            // The forged record looks newer, but never hides the valid one.
            assertEquals(original, record(validator.merge(group.roster, roster), second.id))
            assertEquals(original, record(validator.merge(roster, group.roster), second.id))
        }
    }

    @Test
    fun `a member can not take over the founder or another device`() {
        // The second device signs records under the ids of the founder and of the third
        // device, with keys of its own and stamps far in the future.
        val stolen = group.newKeys(Random(7))
        fun keysOf(id: String) = stolen.withId(id)
        val fakeFounder = keysOf(founder.id)
        val forgedFounder = group.admit(fakeFounder, by = fakeFounder, at = at(99_000, second.id))
        val forgedThird = group.admit(keysOf(third.id), by = second, at = at(99_000, second.id))
        val received = listOf(
            Roster(founder.id, listOf(forgedFounder)),
            group.roster.copy(devices = group.roster.devices - record(group.roster, third.id) + forgedThird),
        )
        // The forged founder does not derive the id of the group: nothing of its roster counts.
        assertEquals(emptyList(), validator.validate(received.first()).devices)
        // The forged record of the third device carries valid signatures; only the known
        // identity stops it.
        assertEquals(received.last().devices.size, validator.validate(received.last()).devices.size)
        received.forEach { roster ->
            assertEquals(group.roster.normalized(), validator.merge(group.roster, roster))
        }
    }

    @Test
    fun `a record without an update stamp was updated when it was added`() {
        val original = record(group.roster, second.id)
        val json = P2pJson.encodeToJsonElement(RosterDevice.serializer(), original).jsonObject
        assertEquals(P2pJson.encodeToJsonElement(Hlc.serializer(), original.addedAt), json["updatedAt"])
        val decoded = P2pJson.decodeFromJsonElement(RosterDevice.serializer(), JsonObject(json - "updatedAt"))
        assertEquals(original.addedAt, decoded.updatedAt)
        // The signature covers the record as sent: one without the field is another record.
        assertFalse(validator.isSelfSigned(decoded))
    }

    @Test
    fun `every device of a fresh group is active`() {
        assertEquals(group.keys.map { it.id }.toSet(), group.roster.activeDevices(crypto::sha256).map { it.id }.toSet())
        assertEquals(group.roster, validator.validate(group.roster))
    }

    @Test
    fun `merge is the union and is commutative associative and idempotent`() {
        val seed = 21
        val random = Random(seed)
        val rosters = List(6) { index ->
            val devices = group.roster.devices
                .filter { random.nextBoolean() || it.id == founder.id }
                .map { device ->
                    // Some copies carry a newer name of the second device.
                    val renames = index % 3
                    val renamed = device.id == second.id && renames > 0
                    if (renamed) rename(device, "Name $renames", 7_000L + renames) else device
                }
            val revocation = group.revoke(group.roster, third, founder, at(5_000L + index)).revoked
            Roster(founder.id, devices.shuffled(random), if (index % 2 == 0) revocation else emptyList())
        }
        rosters.forEach { a ->
            rosters.forEach { b ->
                assertEquals(RosterCrdt.merge(a, b), RosterCrdt.merge(b, a), "seed=$seed")
                assertEquals(RosterCrdt.merge(a, a), a.normalized(), "seed=$seed")
                rosters.forEach { c ->
                    assertEquals(
                        RosterCrdt.merge(RosterCrdt.merge(a, b), c),
                        RosterCrdt.merge(a, RosterCrdt.merge(b, c)),
                        "seed=$seed",
                    )
                }
            }
        }
    }

    @Test
    fun `a revocation is sticky`() {
        val revoked = group.revoke(group.roster, second, founder, at(5_000))
        // An older copy of the roster, without the revocation, does not bring the device back.
        val merged = RosterCrdt.merge(group.roster, revoked)
        assertFalse(group.activity(merged).isActive(second.id))
        assertFalse(group.activity(RosterCrdt.merge(merged, group.roster)).isActive(second.id))
        // Nor does a fresh admission of the same id.
        val others = merged.devices.filterNot { it.id == second.id }
        val readmitted = merged.copy(devices = others + group.admit(second, founder, at(9_000)))
        assertFalse(group.activity(readmitted).isActive(second.id))
        assertEquals(at(5_000), group.activity(merged).revokedAt(second.id))
    }

    /** Returns the roster of the group in which the second device admitted [device] at [wall]. */
    private fun admittedBySecond(device: TestDeviceKeys, wall: Long): Roster =
        group.roster.copy(devices = group.roster.devices + group.admit(device, by = second, at = at(wall, second.id)))

    @Test
    fun `a revocation seals the devices the revoked device admitted`() {
        val known = group.newKeys(Random(3))
        val revoked = group.revoke(admittedBySecond(known, 4_000), second, founder, at(5_000))
        assertEquals(listOf(group.identityHash(record(revoked, known.id))), revoked.revoked.single().admitted)
        // Once revoked, the second device admits two more: one stamped before its revocation.
        val backdated = group.newKeys(Random(2))
        val late = group.newKeys(Random(9))
        val roster = revoked.copy(
            devices = revoked.devices +
                group.admit(backdated, by = second, at = at(4_500, second.id)) +
                group.admit(late, by = second, at = at(6_000, second.id)),
        )
        val activity = group.activity(validator.validate(roster))
        assertTrue(activity.isActive(known.id))
        assertFalse(activity.isActive(backdated.id))
        assertFalse(activity.isActive(late.id))
        // Neither can its devices admit any.
        val grandchild = group.newKeys(Random(14))
        val grandchildRecord = group.admit(grandchild, by = backdated, at = at(4_600, backdated.id))
        val deeper = roster.copy(devices = roster.devices + grandchildRecord)
        assertFalse(group.activity(validator.validate(deeper)).isActive(grandchild.id))
    }

    @Test
    fun `a revoked device can not admit a new identity with the key of a device it sealed`() {
        // The second device admits a device of the thief, which the founder keeps when it
        // removes the second one; later the founder removes the thief's device too.
        val sealed = group.newKeys(Random(3))
        var roster = group.revoke(admittedBySecond(sealed, 4_000), second, founder, at(5_000))
        roster = group.revoke(roster, sealed, founder, at(6_000))
        assertFalse(group.activity(validator.validate(roster)).isActive(sealed.id))
        // The second device admits a new id with every key of the sealed device, which the
        // thief holds; it self-signs with the sealed device's key.
        val fresh = sealed.withId(Random(18).nextUuid())
        val forged = roster.copy(
            devices = roster.devices + group.admit(fresh, by = second, at = at(4_500, second.id)),
        )
        val validated = validator.validate(forged)
        assertTrue(fresh.id in validated.devices.map { it.id })
        assertFalse(group.activity(validated).isActive(fresh.id))
    }

    @Test
    fun `a sealed admission names the keys of the device`() {
        val known = group.newKeys(Random(3))
        val revoked = group.revoke(admittedBySecond(known, 4_000), second, founder, at(5_000))
        // The second device signs another record under the id of the sealed device, with other keys.
        val other = group.newKeys(Random(12))
        val impostor = other.withId(known.id)
        val forged = revoked.copy(
            devices = revoked.devices.filterNot { it.id == known.id } +
                group.admit(impostor, by = second, at = at(4_000, second.id)),
        )
        assertFalse(group.activity(validator.validate(forged)).isActive(known.id))
    }

    @Test
    fun `the seal is signed`() {
        val revoked = group.revoke(group.roster, second, founder, at(5_000))
        val widened = revoked.revoked.single().let {
            it.copy(admitted = (it.admitted + group.identityHash(record(group.roster, third.id))).sorted())
        }
        assertEquals(emptyList(), validator.validate(revoked.copy(revoked = listOf(widened))).revoked)
    }

    @Test
    fun `a device that left can admit no one with its old key`() {
        val left = group.revoke(group.roster, second, by = second, at = at(5_000, second.id))
        val stranger = group.newKeys(Random(13))
        val roster = left.copy(devices = left.devices + group.admit(stranger, by = second, at = at(4_000, second.id)))
        val activity = group.activity(validator.validate(roster))
        assertFalse(activity.isActive(second.id))
        assertFalse(activity.isActive(stranger.id))
    }

    @Test
    fun `a device key alone can neither admit nor remove a device`() {
        // A thief holds the second device, unlocked, but not its master password: it signs
        // with the device key, never with the authority key.
        val stranger = group.newKeys(Random(15))
        val at = at(6_000, second.id)
        val admitted = group.signer.admit(group.record(stranger, by = second, at = at), second.signPrivate)
        val removal = group.signer.revoke(group.roster, third.id, at, second.id, second.signPrivate)
        val roster = group.roster.copy(devices = group.roster.devices + admitted, revoked = listOf(removal))
        val validated = validator.validate(roster)
        assertFalse(stranger.id in validated.devices.map { it.id })
        assertEquals(emptyList(), validated.revoked)
        assertTrue(group.activity(validated).isActive(third.id))
    }

    @Test
    fun `a device with an authority key can not leave with its device key`() {
        val left = group.signer.leave(group.roster, second.id, at(6_000, second.id), second.signPrivate)
        val validated = validator.validate(group.roster.copy(revoked = listOf(left)))
        assertEquals(emptyList(), validated.revoked)
        assertTrue(group.activity(validated).isActive(second.id))
    }

    @Test
    fun `a device key alone can not take the founder and its devices out`() {
        // A thief holds the founder, unlocked: it signs the leaving of the founder with the
        // device key, and seals none of the devices the founder admitted.
        val alone = group.roster.copy(devices = listOf(record(group.roster, founder.id)))
        val left = group.signer.leave(alone, founder.id, at(6_000), founder.signPrivate)
        assertEquals(emptyList(), left.admitted)
        val validated = validator.validate(group.roster.copy(revoked = listOf(left)))
        assertEquals(emptyList(), validated.revoked)
        assertEquals(group.keys.map { it.id }.toSet(), group.activity(validated).activeIds)
    }

    @Test
    fun `a device leaves with its authority key and keeps the devices it admitted`() {
        val left = group.signer.leave(group.roster, founder.id, at(6_000), founder.authorityPrivate)
        val validated = validator.validate(group.roster.copy(revoked = listOf(left)))
        assertEquals(listOf(left), validated.revoked)
        assertEquals(setOf(second.id, third.id), group.activity(validated).activeIds)
    }

    @Test
    fun `a device without an authority key leaves with its device key, and that key removes no other device`() {
        val keys = group.newKeys(Random(17))
        val unsigned = group.record(keys, by = founder, at = at(4_000)).copy(authorityPub = "")
        val record = group.signer.admit(group.signer.selfSign(unsigned, keys.signPrivate), founder.authorityPrivate)
        val roster = group.roster.copy(devices = group.roster.devices + record)
        val left = group.signer.leave(roster, keys.id, at(6_000, keys.id), keys.signPrivate)
        // The device key of that device signs the removal of the third one.
        val forged = group.signer.revoke(roster, third.id, at(6_000, keys.id), keys.id, keys.signPrivate)
        val validated = validator.validate(roster.copy(revoked = listOf(left, forged)))
        assertEquals(listOf(left), validated.revoked)
        assertFalse(group.activity(validated).isActive(keys.id))
        assertTrue(group.activity(validated).isActive(third.id))
    }

    @Test
    fun `a peer can not swap the admission of a device for one it signed`() {
        // The second device removed the third one.
        val revoked = group.revoke(group.roster, third, by = second, at = at(5_000, second.id))
        val original = record(revoked, second.id)
        // A peer founds a group of its own under the id of the founder and admits the second
        // device into it, keeping the self-signature of the second device. Its keys are
        // picked so that its record wins the tie of two records with one update stamp.
        val (fakeFounder, forged) = (100 until 200).asSequence()
            .map { seed ->
                val keys = group.newKeys(Random(seed)).withId(founder.id)
                group.admit(keys, by = keys, at = at(1_000)) to group.signer.admit(original, keys.authorityPrivate)
            }
            .first { (_, forged) -> RosterCrdt.recordOrder.compare(forged, original) > 0 }
        val incoming = Roster(founder.id, devices = listOf(fakeFounder, forged))
        // On its own, the received roster holds nothing: its founder does not derive the id
        // of the group.
        assertEquals(emptyList(), validator.validate(incoming).devices)
        val merged = validator.merge(revoked, incoming)
        listOf(merged, validator.merge(merged, revoked), validator.merge(merged, incoming)).forEach { roster ->
            assertEquals(original, record(roster, second.id))
            val activity = group.activity(roster)
            assertTrue(activity.isActive(second.id))
            // The revocation the second device signed stays.
            assertFalse(activity.isActive(third.id))
        }
    }

    @Test
    fun `the authority key of a device never changes`() {
        // The second device, with its device key alone, signs a newer record that names an
        // authority key of the thief.
        val original = record(group.roster, second.id)
        val thief = group.newKeys(Random(16))
        val swapped = group.signer.selfSign(
            original.copy(authorityPub = thief.authorityPublic, updatedAt = at(9_000, second.id)),
            second.signPrivate,
        )
        val roster = group.roster.copy(devices = group.roster.devices - original + swapped)
        assertEquals(original, record(validator.merge(group.roster, roster), second.id))
        assertEquals(original, record(validator.merge(roster, group.roster), second.id))
    }

    @Test
    fun `a revoked device never gets back in`() {
        val revoked = group.revoke(group.roster, second, founder, at(5_000))
        // The second device revokes every other device, stamped before its own revocation,
        // and admits a device of its own, which revokes the founder once more.
        val own = group.newKeys(Random(11))
        var roster = group.revoke(revoked, founder, by = second, at = at(4_000, second.id))
        roster = group.revoke(roster, third, by = second, at = at(4_001, second.id))
        roster = roster.copy(devices = roster.devices + group.admit(own, by = second, at = at(4_002, second.id)))
        roster = group.revoke(roster, founder, by = own, at = at(4_003, own.id))
        val activity = group.activity(validator.validate(roster))
        // It can remove the others, but it stays revoked, and its own device never counts.
        assertEquals(emptySet(), activity.activeIds)
        assertEquals(at(5_000), activity.revokedAt(second.id))
    }

    @Test
    fun `a device joins at its admission, up to the clock skew, and revocations never undo it`() {
        val late = group.newKeys(Random(8))
        val roster = group.roster.copy(devices = group.roster.devices + group.admit(late, by = founder, at = at(9_000)))
        val activity = group.activity(validator.validate(roster))
        assertTrue(activity.hasJoinedBy(late.id, at(9_000, late.id)))
        assertFalse(activity.hasJoinedBy(late.id, at(8_999, late.id)))
        assertTrue(activity.hasJoinedBy(late.id, at(8_000, late.id), clockSkewMs = 1_000))
        assertFalse(activity.hasJoinedBy(late.id, at(7_999, late.id), clockSkewMs = 1_000))
        val revoked = group.activity(validator.validate(group.revoke(roster, late, founder, at(9_500))))
        assertTrue(revoked.hasJoinedBy(late.id, at(10_000, late.id)))
        assertFalse(revoked.hasJoinedBy(group.newKeys(Random(10)).id, at(10_000)))
    }

    @Test
    fun `the admission chain must reach the founder`() {
        val stranger = group.newKeys(Random(4))
        val invited = group.newKeys(Random(5))
        val roster = group.roster.copy(
            devices = group.roster.devices +
                group.admit(stranger, by = stranger, at = at(2_000, stranger.id)) +
                group.admit(invited, by = stranger, at = at(3_000, stranger.id)),
        )
        val active = group.activity(roster).activeIds
        assertFalse(stranger.id in active)
        assertFalse(invited.id in active)
    }

    @Test
    fun `records and revocations with invalid signatures are dropped`() {
        val forgedLabel = group.roster.devices[1].copy(label = "Evil")
        val forgedAdmission = group.roster.devices[2].copy(admission = group.roster.devices[1].admission)
        val forgedRevocation =
            group.signer.revoke(group.roster, second.id, at(5_000), founder.id, third.authorityPrivate)
        val roster = Roster(
            founder = founder.id,
            devices = listOf(group.roster.devices[0], forgedLabel, forgedAdmission),
            revoked = listOf(forgedRevocation),
        )
        val valid = validator.validate(roster)
        assertEquals(listOf(founder.id), valid.devices.map { it.id })
        assertEquals(emptyList(), valid.revoked)
    }

    @Test
    fun `devices admitted by a dropped record are dropped too`() {
        val child = group.newKeys(Random(6))
        val forgedParent = group.roster.devices[1].copy(selfSig = group.roster.devices[2].selfSig)
        val orphan = group.admit(child, by = second, at = at(4_000, second.id))
        val roster = group.roster.copy(devices = listOf(group.roster.devices[0], forgedParent, orphan))
        assertEquals(listOf(founder.id), validator.validate(roster).devices.map { it.id })
    }
}
