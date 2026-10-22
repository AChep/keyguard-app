package com.artemchep.keyguard.common.service.agent

/**
 * The frames of every private agent channel: a four-byte big-endian length,
 * then that many bytes of payload. Each channel picks its largest packet;
 * an empty one is never valid.
 */
object AgentIpcFraming {
    const val HEADER_SIZE = 4

    /** The largest packet of the SSH and GPG agents' channels. */
    const val MAX_PACKET_SIZE = 16 * 1024 * 1024

    fun isValidSize(size: Int, maxPacketSize: Int): Boolean = size in 1..maxPacketSize

    /** Throws for a [size] that the channel must not carry. */
    fun requireValidSize(size: Int, maxPacketSize: Int) {
        require(isValidSize(size, maxPacketSize)) { "Invalid message length: $size" }
    }

    /** The length in [header], which can be outside of any valid range. */
    fun decodeHeader(header: ByteArray): Int {
        require(header.size == HEADER_SIZE) { "Invalid header length: ${header.size}" }
        return header.fold(0) { value, byte -> (value shl Byte.SIZE_BITS) or (byte.toInt() and BYTE_MASK) }
    }

    fun encodeHeader(size: Int, maxPacketSize: Int): ByteArray {
        requireValidSize(size, maxPacketSize)
        return ByteArray(HEADER_SIZE) { index ->
            (size ushr ((HEADER_SIZE - 1 - index) * Byte.SIZE_BITS)).toByte()
        }
    }
}

private const val BYTE_MASK = 0xff
