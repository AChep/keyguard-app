package com.artemchep.keyguard.util.io

import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CopyingRawSourceTest {
    @Test
    fun `tees every byte into the output and counts them`() {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val output = Buffer()
        val source = CopyingRawSource(
            input = Buffer().apply { write(payload) },
            output = output,
        )

        val read = source.buffered().readByteArray()
        source.close()

        assertContentEquals(payload, read)
        assertContentEquals(payload, output.readByteArray())
        assertEquals(payload.size.toLong(), source.size)
    }

    @Test
    fun `rejects reads after close`() {
        val source = CopyingRawSource(
            input = Buffer(),
            output = Buffer(),
        )
        source.close()

        assertFailsWith<IllegalStateException> {
            source.readAtMostTo(Buffer(), 1L)
        }
    }
}
