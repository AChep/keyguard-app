package com.artemchep.keyguard.feature.home.vault.screen

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class VaultListWriteCapabilityTest {
    @Test
    fun `confirmed no-account state denies writes without repository permission`() = runTest {
        val values = vaultListWriteCapabilityFlow(
            hasAccountsFlow = flowOf(false),
            capabilityFlow = flowOf(WriteCapability.Unknown),
        ).toList()

        assertEquals(WriteCapability.Denied, values.last())
    }

    @Test
    fun `existing account preserves tri-state write permission`() = runTest {
        val unknownValues = vaultListWriteCapabilityFlow(
            hasAccountsFlow = flowOf(true),
            capabilityFlow = flowOf(WriteCapability.Unknown),
        ).toList()
        val knownValues = vaultListWriteCapabilityFlow(
            hasAccountsFlow = flowOf(true),
            capabilityFlow = flowOf(
                WriteCapability.Unknown,
                WriteCapability.Allowed,
                WriteCapability.Denied,
            ),
        ).toList()

        assertEquals(WriteCapability.Unknown, unknownValues.last())
        assertEquals(
            listOf(
                WriteCapability.Unknown,
                WriteCapability.Allowed,
                WriteCapability.Denied,
            ),
            knownValues,
        )
    }
}
