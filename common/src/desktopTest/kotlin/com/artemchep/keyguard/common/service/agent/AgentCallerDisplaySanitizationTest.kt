package com.artemchep.keyguard.common.service.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentCallerDisplaySanitizationTest {
    @Test
    fun `unsafe caller display predicate covers every prohibited category`() {
        assertTrue(isUnsafeAgentCallerDisplayCodePoint('\n'.code))
        assertTrue(isUnsafeAgentCallerDisplayCodePoint(0x202E))
        assertTrue(isUnsafeAgentCallerDisplayCodePoint(0x2028))
        assertTrue(isUnsafeAgentCallerDisplayCodePoint(0x2029))
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

    @Test
    fun `caller display values are escaped and bounded`() {
        assertEquals(
            "Example\\u202e Client",
            "Example\u202E Client".sanitizedAgentDisplayValue(256),
        )
        assertEquals(
            "First\\u2028Second\\u2029Third",
            "First\u2028Second\u2029Third".sanitizedAgentDisplayValue(256),
        )
        assertEquals("Example 😀 Client", "Example 😀 Client".sanitizedAgentDisplayValue(256))
        assertEquals("123…", "12345".sanitizedAgentDisplayValue(4))
        assertEquals("A…", "A😀B".sanitizedAgentDisplayValue(3))
    }
}
