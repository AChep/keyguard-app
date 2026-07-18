package app.keemobile.kotpass.io

import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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
}
