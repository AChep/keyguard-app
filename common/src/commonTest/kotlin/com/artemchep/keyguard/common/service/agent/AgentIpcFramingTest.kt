package com.artemchep.keyguard.common.service.agent

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentIpcFramingTest {
    @Test
    fun headersAreBigEndianLengths() {
        val header = AgentIpcFraming.encodeHeader(0x01020304, Int.MAX_VALUE)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), header)
        assertEquals(0x01020304, AgentIpcFraming.decodeHeader(header))
        assertEquals(-1, AgentIpcFraming.decodeHeader(ByteArray(4) { 0xff.toByte() }))
    }

    @Test
    fun emptyAndOversizePacketsAreInvalidInBothDirections() {
        val max = 1024
        assertTrue(AgentIpcFraming.isValidSize(1, max))
        assertTrue(AgentIpcFraming.isValidSize(max, max))
        for (size in listOf(-1, 0, max + 1)) {
            assertFalse(AgentIpcFraming.isValidSize(size, max))
            assertFailsWith<IllegalArgumentException> { AgentIpcFraming.encodeHeader(size, max) }
            assertFailsWith<IllegalArgumentException> { AgentIpcFraming.requireValidSize(size, max) }
        }
    }

    @Test
    fun headersHaveFourBytes() {
        assertFailsWith<IllegalArgumentException> { AgentIpcFraming.decodeHeader(ByteArray(3)) }
    }
}
