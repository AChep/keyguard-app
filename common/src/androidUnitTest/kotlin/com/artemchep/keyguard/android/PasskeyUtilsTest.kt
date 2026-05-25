package com.artemchep.keyguard.android

import org.junit.Assert.assertEquals
import org.junit.Test

class PasskeyUtilsTest {
    @Test
    fun `authenticator data flags use WebAuthn bits`() {
        assertEquals(
            0x01,
            flags(userPresence = true),
        )
        assertEquals(
            0x04,
            flags(userVerification = true),
        )
        assertEquals(
            0x08,
            flags(backupEligibility = true),
        )
        assertEquals(
            0x10,
            flags(backupState = true),
        )
        assertEquals(
            0x40,
            flags(attestationData = true),
        )
        assertEquals(
            0x80,
            flags(extensionData = true),
        )
    }

    @Test
    fun `authenticator data flags combine attestation and extension bits`() {
        assertEquals(
            0xc0,
            flags(
                extensionData = true,
                attestationData = true,
            ),
        )
    }

    private fun flags(
        extensionData: Boolean = false,
        attestationData: Boolean = false,
        backupState: Boolean = false,
        backupEligibility: Boolean = false,
        userVerification: Boolean = false,
        userPresence: Boolean = false,
    ): Int = authDataFlags(
        extensionData = extensionData,
        attestationData = attestationData,
        backupState = backupState,
        backupEligibility = backupEligibility,
        userVerification = userVerification,
        userPresence = userPresence,
    ).toInt() and 0xff
}
