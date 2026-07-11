package com.artemchep.keyguard.common.service.agent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentCallerDisplaySanitizationTest {
    @Test
    fun `unsafe caller display predicate covers every prohibited category`() {
        assertTrue(isUnsafeAgentCallerDisplayCodePoint('\n'.code))
        assertTrue(isUnsafeAgentCallerDisplayCodePoint(0x202E))
        assertTrue(isUnsafeAgentCallerDisplayCodePoint(0xD800))
        assertTrue(isUnsafeAgentCallerDisplayCodePoint(0xE000))
        assertTrue(isUnsafeAgentCallerDisplayCodePoint(0x0378))
    }

    @Test
    fun `unsafe caller display predicate permits ordinary text and whitespace`() {
        assertFalse(isUnsafeAgentCallerDisplayCodePoint('A'.code))
        assertFalse(isUnsafeAgentCallerDisplayCodePoint(' '.code))
        assertFalse(isUnsafeAgentCallerDisplayCodePoint(0x1F600))
    }
}
