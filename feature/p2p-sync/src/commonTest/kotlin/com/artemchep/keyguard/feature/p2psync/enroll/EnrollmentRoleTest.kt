package com.artemchep.keyguard.feature.p2psync.enroll

import com.artemchep.keyguard.feature.p2psync.model.PairingCode
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnrollmentRoleTest {
    private fun code(member: Boolean) = PairingCode(
        psk = ByteArray(PairingCode.KEY_SIZE) { 1 },
        pub = ByteArray(PairingCode.KEY_SIZE) { 2 },
        member = member,
        expiresAt = 0L,
        scope = SyncScope.APP,
    )

    @Test
    fun `a displaying member admits`() {
        assertEquals(EnrollmentRole.MEMBER, chooseEnrollmentRole(isMember = true, scanned = null))
    }

    @Test
    fun `a displaying device without a group joins`() {
        assertEquals(EnrollmentRole.JOINER, chooseEnrollmentRole(isMember = false, scanned = null))
    }

    @Test
    fun `a scanner without a group founds one for a displayer without a group`() {
        assertEquals(EnrollmentRole.MEMBER, chooseEnrollmentRole(isMember = false, scanned = code(member = false)))
    }

    @Test
    fun `a scanning member admits a displayer without a group`() {
        assertEquals(EnrollmentRole.MEMBER, chooseEnrollmentRole(isMember = true, scanned = code(member = false)))
    }

    @Test
    fun `a scanner without a group joins a displaying member`() {
        assertEquals(EnrollmentRole.JOINER, chooseEnrollmentRole(isMember = false, scanned = code(member = true)))
    }

    @Test
    fun `two members can not pair`() {
        assertNull(chooseEnrollmentRole(isMember = true, scanned = code(member = true)))
    }

    @Test
    fun `the roles of the two devices always differ`() {
        for (displayerIsMember in listOf(false, true)) {
            for (scannerIsMember in listOf(false, true)) {
                val displayer = chooseEnrollmentRole(isMember = displayerIsMember, scanned = null)
                val code = code(member = displayer == EnrollmentRole.MEMBER)
                val scanner = chooseEnrollmentRole(isMember = scannerIsMember, scanned = code)
                if (scanner != null) {
                    assertEquals(setOf(EnrollmentRole.MEMBER, EnrollmentRole.JOINER), setOf(displayer, scanner))
                }
            }
        }
    }
}
