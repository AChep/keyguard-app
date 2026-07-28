package com.artemchep.keyguard.android.sshagent

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SshAgentSessionParameterValidationTest {
    @Test
    fun `valid bounded base64 parameter is accepted`() {
        val value = Base64.getEncoder().encodeToString(ByteArray(32) { 0x22 })

        assertTrue(
            isValidAndroidSshAgentSessionParameter(
                value = value,
                expectedDecodedSize = 32,
            ),
        )
    }

    @Test
    fun `oversized encoded parameter is rejected before decoding`() {
        assertFalse(
            isValidAndroidSshAgentSessionParameter(
                value = "A".repeat(129),
                expectedDecodedSize = 32,
            ),
        )
    }

    @Test
    fun `wrong decoded size and malformed base64 are rejected`() {
        assertFalse(
            isValidAndroidSshAgentSessionParameter(
                value = Base64.getEncoder().encodeToString(ByteArray(31)),
                expectedDecodedSize = 32,
            ),
        )
        assertFalse(
            isValidAndroidSshAgentSessionParameter(
                value = "not base64!",
                expectedDecodedSize = 32,
            ),
        )
    }
}
