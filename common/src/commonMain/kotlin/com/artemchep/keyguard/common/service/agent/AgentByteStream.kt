package com.artemchep.keyguard.common.service.agent

/**
 * The bytes of one private agent connection. Cancelling any call closes the
 * connection.
 */
class AgentByteStream(
    /**
     * Reads at least one byte, and at most `length`, into the buffer at `offset`.
     * Returns the count, or -1 at the end of the stream.
     */
    val read: suspend (buffer: ByteArray, offset: Int, length: Int) -> Int,
    /** Writes `length` bytes of the buffer from `offset`. */
    val write: suspend (buffer: ByteArray, offset: Int, length: Int) -> Unit,
    /**
     * Returns once the first unread byte arrives, without consuming it, at the
     * end of the stream, on any failure, or after a local close.
     */
    val awaitInputOrDisconnect: suspend () -> Unit,
)

/** A frame whose size is outside of the channel's range. Its text never holds the size. */
internal class InvalidAgentFrameException : IllegalArgumentException("invalid_frame")

/**
 * The packets of an [AgentByteStream], each framed as [AgentIpcFraming] describes.
 * Sizes are checked before a body is allocated and before any byte is written.
 */
internal class FramedAgentPacketChannel(
    private val stream: AgentByteStream,
    private val maxPacketSize: Int,
) {
    /**
     * The next packet, or null when the stream ends, at a frame boundary or within
     * a truncated frame. Throws [InvalidAgentFrameException] for a size outside of
     * the channel's range. A body that is not read in full is zeroed, whatever
     * ends the read, including cancellation.
     */
    suspend fun readPacket(): ByteArray? {
        val header = ByteArray(AgentIpcFraming.HEADER_SIZE)
        if (!readFully(header)) {
            return null
        }
        val size = AgentIpcFraming.decodeHeader(header)
        if (!AgentIpcFraming.isValidSize(size, maxPacketSize)) {
            throw InvalidAgentFrameException()
        }
        val body = ByteArray(size)
        var complete = false
        try {
            complete = readFully(body)
        } finally {
            if (!complete) {
                body.fill(0)
            }
        }
        return body.takeIf { complete }
    }

    /** Writes [packet] with its header. Throws [InvalidAgentFrameException] before writing an invalid size. */
    suspend fun writePacket(packet: ByteArray) {
        if (!AgentIpcFraming.isValidSize(packet.size, maxPacketSize)) {
            throw InvalidAgentFrameException()
        }
        val header = AgentIpcFraming.encodeHeader(packet.size, maxPacketSize)
        stream.write(header, 0, header.size)
        stream.write(packet, 0, packet.size)
    }

    /** Fills [buffer]; false when the stream ends first. */
    private suspend fun readFully(buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val count = stream.read(buffer, offset, buffer.size - offset)
            if (count < 0) {
                return false
            }
            offset += count
        }
        return true
    }
}
