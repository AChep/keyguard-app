package com.artemchep.keyguard.android.agentbridge

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentBridgeSessionParameterValidationTest {
    @Test
    fun `valid bounded base64 parameter is accepted`() {
        val value = Base64.getEncoder().encodeToString(ByteArray(32) { 0x22 })

        assertTrue(
            isValidAgentBridgeSessionParameter(
                value = value,
                expectedDecodedSize = 32,
            ),
        )
    }

    @Test
    fun `oversized encoded parameter is rejected before decoding`() {
        assertFalse(
            isValidAgentBridgeSessionParameter(
                value = "A".repeat(129),
                expectedDecodedSize = 32,
            ),
        )
    }

    @Test
    fun `wrong decoded size and malformed base64 are rejected`() {
        assertFalse(
            isValidAgentBridgeSessionParameter(
                value = Base64.getEncoder().encodeToString(ByteArray(31)),
                expectedDecodedSize = 32,
            ),
        )
        assertFalse(
            isValidAgentBridgeSessionParameter(
                value = "not base64!",
                expectedDecodedSize = 32,
            ),
        )
    }
}
