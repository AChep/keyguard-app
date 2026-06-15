package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.util.readNBytesCompat
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals

class InputStreamReadCompatTest {
    @Test
    fun `readBytesCompat returns the requested bytes across short reads`() {
        val input = ChunkedInputStream(
            data = byteArrayOf(1, 2, 3, 4),
            chunkSize = 1,
        )

        val bytes = input.readNBytesCompat(4)

        assertContentEquals(byteArrayOf(1, 2, 3, 4), bytes)
    }

    @Test
    fun `readBytesCompat returns a truncated buffer when the stream ends early`() {
        val input = ChunkedInputStream(
            data = byteArrayOf(5, 6, 7),
            chunkSize = 1,
        )

        val bytes = input.readNBytesCompat(5)

        assertContentEquals(byteArrayOf(5, 6, 7), bytes)
    }

    private class ChunkedInputStream(
        private val data: ByteArray,
        private val chunkSize: Int,
    ) : InputStream() {
        private var position = 0

        override fun read(): Int {
            if (position >= data.size) {
                return -1
            }
            return data[position++].toInt() and 0xff
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (position >= data.size) {
                return -1
            }

            val count = minOf(len, chunkSize, data.size - position)
            System.arraycopy(data, position, b, off, count)
            position += count
            return count
        }
    }
}
