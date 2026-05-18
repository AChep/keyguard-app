package com.artemchep.keyguard.util.messagepack

object MessagePackFrameCodec {
    fun frame(
        content: ByteArray,
    ): ByteArray {
        val header = encodeLengthHeader(content.size)
        val bytes = ByteArray(header.size + content.size)
        header.copyInto(bytes)
        content.copyInto(bytes, destinationOffset = header.size)
        return bytes
    }

    fun readFrames(
        payload: ByteArray,
    ): List<ByteArray> {
        if (payload.isEmpty()) {
            return emptyList()
        }

        val cursor = Cursor(payload)
        val frames = mutableListOf<ByteArray>()
        while (cursor.hasRemaining()) {
            val length = readLengthHeader(cursor)
            if (cursor.remaining < length) {
                throw IllegalArgumentException(
                    "MessagePack message was length ${cursor.remaining} but claimed to be length $length.",
                )
            }
            frames += cursor.readBytes(length)
        }
        return frames
    }

    private fun readLengthHeader(
        cursor: Cursor,
    ): Int {
        var length = 0
        var numBytes = 0
        val maxLength = 5
        var current: Int
        do {
            current = if (cursor.hasRemaining()) {
                cursor.readByte().toInt() and 0xff
            } else {
                throw IllegalArgumentException("The length header was incomplete.")
            }

            length = length or ((current and 0x7f) shl (numBytes * 7))
            numBytes++
        } while (numBytes < maxLength && current and 0x80 != 0)

        if (current and 0x80 != 0 || numBytes == maxLength && current > 0x07) {
            throw IllegalArgumentException("Messages over 2GB in size are not supported.")
        }
        return length
    }

    private fun encodeLengthHeader(
        length: Int,
    ): ByteArray {
        require(length >= 0) {
            "Length must not be negative."
        }

        var remaining = length
        val header = mutableListOf<Byte>()
        do {
            var current = (remaining and 0x7f).toByte()
            remaining = remaining shr 7
            if (remaining > 0) {
                current = (current.toInt() or 0x80).toByte()
            }
            header += current
        } while (remaining > 0)
        return header.toByteArray()
    }

    private class Cursor(
        private val bytes: ByteArray,
    ) {
        private var offset: Int = 0

        val remaining: Int
            get() = bytes.size - offset

        fun hasRemaining(): Boolean = offset < bytes.size

        fun readByte(): Byte = bytes[offset++]

        fun readBytes(
            length: Int,
        ): ByteArray {
            val result = bytes.copyOfRange(offset, offset + length)
            offset += length
            return result
        }
    }
}
