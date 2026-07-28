package app.keemobile.kotpass.io

import okio.Buffer
import okio.Source
import okio.Timeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StreamingIoTest {
    @Test
    fun maximumLongLimitDoesNotOverflowSentinelRead() {
        val expected = byteArrayOf(1, 2, 3)
        val source = LimitedSource(
            delegate = Buffer().write(expected),
            maximumBytes = Long.MAX_VALUE,
        )
        val output = Buffer()

        assertEquals(expected.size.toLong(), source.read(output, Long.MAX_VALUE))
        assertContentEquals(expected, output.readByteArray())
        assertEquals(-1L, source.read(output, Long.MAX_VALUE))
    }

    @Test
    fun closeOnFailureTransfersOwnershipAfterSuccess() {
        val source = TrackingSource()

        val result = source.closeOnFailure { "success" }

        assertEquals("success", result)
        assertEquals(0, source.closeCalls)
    }

    @Test
    fun closeOnFailureClosesSourceAndSuppressesCloseFailure() {
        val primaryFailure = IllegalStateException("Acquisition failed")
        val closeFailure = IllegalArgumentException("Cleanup failed")
        val source = TrackingSource(closeFailure)

        val actual = assertFailsWith<IllegalStateException> {
            source.closeOnFailure<Unit> {
                throw primaryFailure
            }
        }

        assertSame(primaryFailure, actual)
        assertEquals(1, source.closeCalls)
        assertEquals(listOf(closeFailure), actual.suppressedExceptions)
        assertTrue(source.closed)
    }
}

private class TrackingSource(
    private val closeFailure: Throwable? = null,
) : Source {
    var closeCalls: Int = 0
        private set
    var closed: Boolean = false
        private set

    override fun read(sink: Buffer, byteCount: Long): Long = -1L

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        closeCalls++
        closed = true
        closeFailure?.let { throw it }
    }
}
