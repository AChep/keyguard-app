package com.artemchep.keyguard.util.io

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class LimitedSourceTest {
    @Test
    fun readsASourceOfExactlyTheLimit() {
        val source = ByteArray(11).toSource()
        assertEquals(11, source.limitedTo(11).readByteArray().size)
    }

    @Test
    fun refusesASourcePastTheLimit() {
        val source = ByteArray(11).toSource()
        val e = assertFailsWith<SourceTooLargeException> {
            source.limitedTo(10).readByteArray()
        }
        assertEquals(10, e.maxBytes)
    }

    @Test
    fun readsNoMoreThanOneBytePastTheLimit() {
        val source = Buffer().apply { write(ByteArray(100_000)) }
        assertFailsWith<SourceTooLargeException> {
            source.limitedTo(10).readByteArray()
        }
        assertEquals(100_000L - 11, source.size)
    }

    @Test
    fun closingLeavesTheUpstreamOpen() {
        val source = ByteArray(4).toSource()
        source.limitedTo(10).close()
        assertFalse(source.exhausted())
    }
}
