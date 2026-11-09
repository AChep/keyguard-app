package com.artemchep.keyguard.feature.p2psync.session

import com.artemchep.keyguard.feature.p2psync.readBigEndian
import com.artemchep.keyguard.feature.p2psync.unsignedAt
import com.artemchep.keyguard.feature.p2psync.writeBigEndian

/**
 * One frame: `u8 type | u8 flags | u32 msgId | payload`, big-endian. A message is padded
 * (see [MessagePadding]) and split into frames of at most [FrameCodec.MAX_FRAME_SIZE]
 * bytes; all but the last carry [FrameCodec.FLAG_MORE], the last carries
 * [FrameCodec.FLAG_FIN]. The stream is reliable and ordered, so the frames of a message
 * arrive in order; the frames of a few messages may interleave, see [Reassembler].
 *
 * @property typeCode the type byte; see [type].
 */
class Frame(
    val typeCode: Int,
    val flags: Int,
    val msgId: Int,
    val payload: ByteArray,
) {
    /** The frame type, or `null` if this build does not know it. */
    val type: FrameType? get() = FrameType.of(typeCode)

    /** `true` for the last frame of a message. */
    val isFinal: Boolean get() = flags and FrameCodec.FLAG_FIN != 0

    override fun equals(other: Any?): Boolean = other is Frame &&
        typeCode == other.typeCode &&
        flags == other.flags &&
        msgId == other.msgId &&
        payload.contentEquals(other.payload)

    override fun hashCode(): Int = listOf(typeCode, flags, msgId, payload.contentHashCode()).hashCode()

    override fun toString(): String =
        "Frame(type=${type ?: typeCode}, flags=$flags, msgId=$msgId, size=${payload.size})"
}

/** Why frames could not be read or reassembled; each one ends the session. */
enum class FrameError {
    /** Shorter than the header. */
    TRUNCATED,

    /** Larger than [FrameCodec.MAX_FRAME_SIZE]. */
    OVERSIZED,

    /** Not exactly one of [FrameCodec.FLAG_FIN] and [FrameCodec.FLAG_MORE] is set. */
    INVALID_FLAGS,

    /** A message changed its type midway. */
    TYPE_CHANGED,

    /** A message is larger than its type allows, see [FrameType.maxSize]. */
    MESSAGE_TOO_LARGE,

    /** More than [FrameCodec.MAX_IN_FLIGHT] messages are partially received. */
    TOO_MANY_IN_FLIGHT,

    /** A message is not padded as [MessagePadding] pads it. */
    INVALID_PADDING,

    /** A frame that is not the last of its message carries no bytes. */
    EMPTY_FRAME,
}

/** Thrown by [FrameCodec.decode] for bytes that are not a frame. */
class FrameException(val error: FrameError) : IllegalArgumentException("Invalid frame: $error")

/** Encodes, decodes and splits frames. */
object FrameCodec {
    /** The size of the frame header. */
    const val HEADER_SIZE: Int = 6

    /** The largest frame, header included: 32 KiB. */
    const val MAX_FRAME_SIZE: Int = 32 * 1024

    /** The largest frame payload. */
    const val MAX_PAYLOAD_SIZE: Int = MAX_FRAME_SIZE - HEADER_SIZE

    /**
     * The largest padded message of the framing, 5 MiB: the largest message of any type, an
     * enrollment bundle of 4 MiB, padded. Each type takes up to its own, see [FrameType.maxSize].
     */
    const val MAX_MESSAGE_SIZE: Int = 5 * 1024 * 1024

    /** The largest message before padding; 5 MiB is a padding bucket, so it pads to [MAX_MESSAGE_SIZE]. */
    const val MAX_UNPADDED_MESSAGE_SIZE: Int = MAX_MESSAGE_SIZE - MessagePadding.HEADER_SIZE

    /** The most messages a stream may have partially sent at once. */
    const val MAX_IN_FLIGHT: Int = 4

    /** Marks the last frame of a message. */
    const val FLAG_FIN: Int = 1

    /** Marks a frame that more frames of the same message follow. */
    const val FLAG_MORE: Int = 2

    private const val OFFSET_FLAGS = 1
    private const val OFFSET_MSG_ID = 2

    /** Encodes [frame] to bytes. */
    fun encode(frame: Frame): ByteArray {
        val bytes = ByteArray(HEADER_SIZE + frame.payload.size)
        bytes[0] = frame.typeCode.toByte()
        bytes[OFFSET_FLAGS] = frame.flags.toByte()
        bytes.writeInt(OFFSET_MSG_ID, frame.msgId)
        frame.payload.copyInto(bytes, destinationOffset = HEADER_SIZE)
        return bytes
    }

    /** Decodes a frame, or throws [FrameException]. */
    fun decode(bytes: ByteArray): Frame {
        val error = when {
            bytes.size < HEADER_SIZE -> FrameError.TRUNCATED
            bytes.size > MAX_FRAME_SIZE -> FrameError.OVERSIZED
            bytes.flags().let { it != FLAG_FIN && it != FLAG_MORE } -> FrameError.INVALID_FLAGS
            else -> null
        }
        if (error != null) throw FrameException(error)
        return Frame(
            typeCode = bytes.unsignedAt(0),
            flags = bytes.flags(),
            msgId = bytes.readInt(OFFSET_MSG_ID),
            payload = bytes.copyOfRange(HEADER_SIZE, bytes.size),
        )
    }

    /** Decodes a frame, or returns `null` for bytes that are not a frame. */
    fun decodeOrNull(bytes: ByteArray): Frame? = try {
        decode(bytes)
    } catch (expected: FrameException) {
        null
    }

    /** Pads the message [payload] and splits it into frames. */
    fun split(type: FrameType, msgId: Int, payload: ByteArray, maxPayload: Int = MAX_PAYLOAD_SIZE): List<Frame> {
        require(maxPayload > 0) { "The frame payload limit must be positive" }
        require(payload.size <= MAX_UNPADDED_MESSAGE_SIZE) {
            "The message is larger than $MAX_UNPADDED_MESSAGE_SIZE bytes"
        }
        val padded = MessagePadding.pad(payload)
        return (padded.indices step maxPayload).map { start ->
            val end = minOf(start + maxPayload, padded.size)
            Frame(
                typeCode = type.code,
                flags = if (end == padded.size) FLAG_FIN else FLAG_MORE,
                msgId = msgId,
                payload = padded.copyOfRange(start, end),
            )
        }
    }

    private fun ByteArray.flags(): Int = unsignedAt(OFFSET_FLAGS)

    private fun ByteArray.writeInt(index: Int, value: Int) = writeBigEndian(index, Int.SIZE_BYTES, value.toLong())

    private fun ByteArray.readInt(index: Int): Int = readBigEndian(index, Int.SIZE_BYTES).toInt()
}
