package com.artemchep.keyguard.feature.p2psync.roster

import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.testing.TestCrypto
import com.artemchep.keyguard.feature.p2psync.testing.TestGroup
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/** How many signatures a merge verifies: a peer that sends the same records again costs nothing. */
class RosterValidatorTest {
    private val group = TestGroup(TestCrypto(23), size = 3, random = Random(23))
    private val crypto = CountingCrypto(group.crypto)
    private val validator = RosterValidator(crypto, group.groupId)
    private val founder = group.keys[0]
    private val second = group.keys[1]
    private val third = group.keys[2]

    private fun at(wall: Long, node: String = founder.id) = Hlc(wall, 0, node)

    /** The roster of the group with [third] removed: records and a revocation. */
    private val local = group.revoke(group.roster, third, founder, at(5_000))

    @Test
    fun `a roster of records the local one holds is merged without a check`() {
        assertSame(local, validator.mergeOrNull(local, Roster(founder = founder.id)))
        val some = Roster(founder = founder.id, devices = local.devices.take(1), revoked = local.revoked)
        assertSame(local, validator.mergeOrNull(local, some))
        assertSame(local, validator.merge(local, local))
        assertEquals(0, crypto.verifies)
    }

    @Test
    fun `a merge verifies only the signatures it did not verify yet`() {
        val validated = validator.validate(local)
        assertEquals(local, validated)
        // A self-signature and an admission per record, and the revocation.
        assertEquals(2 * local.devices.size + 1, crypto.verifies)

        crypto.verifies = 0
        assertEquals(validated, validator.validate(validated))
        assertEquals(0, crypto.verifies)

        // The second device signs its record again: only that record is checked.
        val original = validated.devices.single { it.id == second.id }
        val renamed = group.signer.update(original, at(7_000, second.id), second.signPrivate, label = "Renamed")
        val merged = assertNotNull(validator.mergeOrNull(validated, Roster(founder.id, listOf(renamed))))
        assertEquals(renamed, merged.devices.single { it.id == second.id })
        assertEquals(2, crypto.verifies)
    }

    /** A [P2pCrypto] that counts the signatures it checks. */
    private class CountingCrypto(
        private val crypto: P2pCrypto,
    ) : P2pCrypto by crypto {
        var verifies = 0

        override fun ed25519Verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
            verifies += 1
            return crypto.ed25519Verify(publicKey, data, signature)
        }
    }
}
