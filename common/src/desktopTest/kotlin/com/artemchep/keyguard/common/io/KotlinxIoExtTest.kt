package com.artemchep.keyguard.common.io

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinxIoExtTest {
    @Test
    fun `writeText writes utf8 bytes into sink`() {
        val sink = Buffer()
        val data = "payload Привіт"

        sink.writeText(data)

        assertContentEquals(data.encodeToByteArray(), sink.readByteArray())
    }

    @Test
    fun `useLines returns no lines for empty source`() {
        val lines = Buffer().useLines { it.toList() }

        assertEquals(emptyList(), lines)
    }

    @Test
    fun `useLines reads lf terminated lines`() {
        val lines = Buffer()
            .apply {
                writeString("alpha\nbeta\n")
            }
            .useLines { it.toList() }

        assertEquals(listOf("alpha", "beta"), lines)
    }

    @Test
    fun `useLines reads final line without trailing newline`() {
        val lines = Buffer()
            .apply {
                writeString("alpha\nbeta")
            }
            .useLines { it.toList() }

        assertEquals(listOf("alpha", "beta"), lines)
    }

    @Test
    fun `useLines normalizes crlf lines`() {
        val lines = Buffer()
            .apply {
                writeString("alpha\r\nbeta\r\n")
            }
            .useLines { it.toList() }

        assertEquals(listOf("alpha", "beta"), lines)
    }

    @Test
    fun `useLines closes source after block`() {
        val upstream = object : RawSource {
            private val buffer = Buffer().apply {
                writeString("alpha")
            }
            var closed = false

            override fun readAtMostTo(
                sink: Buffer,
                byteCount: Long,
            ): Long = buffer.readAtMostTo(sink, byteCount)

            override fun close() {
                closed = true
            }
        }

        upstream.buffered().useLines { lines ->
            assertEquals(listOf("alpha"), lines.toList())
        }

        assertTrue(upstream.closed)
    }
}
