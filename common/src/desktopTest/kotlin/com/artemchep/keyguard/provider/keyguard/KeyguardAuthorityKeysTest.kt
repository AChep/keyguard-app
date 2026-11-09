package com.artemchep.keyguard.provider.keyguard

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.provider.keyguard.applink.AppLinkTestDevice
import com.artemchep.keyguard.provider.keyguard.applink.TEST_MASTER_PASSWORD
import com.artemchep.keyguard.provider.keyguard.applink.saveAppLinkMembership
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyguardAuthorityKeysTest {
    @Test
    fun `only the master password opens the authority key`() = runTest {
        val device = AppLinkTestDevice("Laptop")
        val keys = device.authorityKeys
        val created = keys.create(TEST_MASTER_PASSWORD).bind()

        val opened = assertNotNull(keys.open(created.authority, TEST_MASTER_PASSWORD).bind())
        assertContentEquals(created.seed, opened.seed)
        // The password is trimmed, as the one that unlocks the vault.
        assertNotNull(keys.open(created.authority, " $TEST_MASTER_PASSWORD ").bind())
        assertNull(keys.open(created.authority, "another password").bind())
        // The seal never holds the seed in the clear.
        assertNotEquals(created.authority.sealedSeed, created.authority.publicKeyBase64)
    }

    @Test
    fun `a password change re-seals the key of every group, and keeps the key`() = runTest {
        val device = AppLinkTestDevice("Laptop")
        device.link()
        val before = assertNotNull(device.membership().device.authority)

        val prepared = device.authorityKeys.prepareReseals(device.dbManager, TEST_MASTER_PASSWORD, "new password")
        // The preparation writes nothing: the database is not re-keyed yet.
        assertEquals(before, device.membership().device.authority)
        assertEquals(listOf(P2pGroupId.APP_LINK_ID), prepared.reseals.map { it.groupId })
        assertTrue(prepared.stale.isEmpty())

        val skipped = commitReseals(device.dbManager, prepared.reseals) { throw it }

        assertTrue(skipped.isEmpty())
        val after = assertNotNull(device.membership().device.authority)
        assertEquals(before.publicKeyBase64, after.publicKeyBase64)
        assertNull(device.authorityKeys.open(after, TEST_MASTER_PASSWORD).bind())
        assertNotNull(device.authorityKeys.open(after, "new password").bind())
    }

    @Test
    fun `a key the old password does not open is reported as stale`() = runTest {
        val device = AppLinkTestDevice("Laptop")
        device.link()
        val before = device.membership().device.authority

        val prepared = device.authorityKeys.prepareReseals(device.dbManager, "not the password", "new password")

        assertTrue(prepared.reseals.isEmpty())
        assertEquals(listOf(P2pGroupId.APP_LINK_ID), prepared.stale)
        assertEquals(before, device.membership().device.authority)
    }

    @Test
    fun `a key that changed after the preparation stays, and is reported`() = runTest {
        val device = AppLinkTestDevice("Laptop")
        device.link()
        val prepared = device.authorityKeys.prepareReseals(device.dbManager, TEST_MASTER_PASSWORD, "new password")
        // Another key replaces the one the preparation re-sealed.
        val replacement = device.authorityKeys.create(TEST_MASTER_PASSWORD).bind().use { it.authority }
        val membership = device.membership()
        device.db.saveAppLinkMembership(membership.copy(device = membership.device.copy(authority = replacement)))

        val skipped = commitReseals(device.dbManager, prepared.reseals) { throw it }

        assertEquals(listOf(P2pGroupId.APP_LINK_ID), skipped)
        assertEquals(replacement, device.membership().device.authority)
    }

    @Test
    fun `a group that is gone after the preparation is not reported`() = runTest {
        val device = AppLinkTestDevice("Laptop")
        device.link()
        val prepared = device.authorityKeys.prepareReseals(device.dbManager, TEST_MASTER_PASSWORD, "new password")
        device.db.p2pGroupQueries.deleteByGroupId(groupId = P2pGroupId.APP_LINK_ID)

        val skipped = commitReseals(device.dbManager, prepared.reseals) { throw it }

        assertTrue(skipped.isEmpty())
    }
}
