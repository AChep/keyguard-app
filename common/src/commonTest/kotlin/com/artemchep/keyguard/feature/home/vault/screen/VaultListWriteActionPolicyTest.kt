package com.artemchep.keyguard.feature.home.vault.screen

import kotlin.test.Test
import kotlin.test.assertEquals

class VaultListWriteActionPolicyTest {
    @Test
    fun `unknown write capability hides actions without showing subscription`() {
        assertEquals(
            VaultListWriteActionPolicy.Hide,
            vaultListWriteActionPolicy(
                capability = WriteCapability.Unknown,
                selectionActive = false,
            ),
        )
    }

    @Test
    fun `authoritative write capability chooses enabled or subscription actions`() {
        assertEquals(
            VaultListWriteActionPolicy.Allow,
            vaultListWriteActionPolicy(
                capability = WriteCapability.Allowed,
                selectionActive = false,
            ),
        )
        assertEquals(
            VaultListWriteActionPolicy.ShowSubscription,
            vaultListWriteActionPolicy(
                capability = WriteCapability.Denied,
                selectionActive = false,
            ),
        )
        assertEquals(
            VaultListWriteActionPolicy.Hide,
            vaultListWriteActionPolicy(
                capability = WriteCapability.Allowed,
                selectionActive = true,
            ),
        )
    }
}
