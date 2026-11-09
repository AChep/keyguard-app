package com.artemchep.keyguard.feature.p2psync.session

/** A whole message, reassembled from its frames. */
class Message(
    val typeCode: Int,
    val msgId: Int,
    val payload: ByteArray,
) {
    /** The message type, or `null` if this build does not know it. */
    val type: FrameType? get() = FrameType.of(typeCode)
}

/** The outcome of handing one frame to a [Reassembler]. */
sealed interface ReassemblyResult {
    /** The frame was stored; its message is not complete yet. */
    data object Pending : ReassemblyResult

    /** The frame completed a [message]. */
    class Complete(val message: Message) : ReassemblyResult

    /** The frame does not fit the framing: the stream is broken, and the session ends. */
    data class Failed(val error: FrameError) : ReassemblyResult
}

/**
 * Joins the frames of one reliable, ordered stream back into messages and removes their
 * padding. A frame of a message id that is not partially received starts a new message;
 * frames of up to [maxInFlight] messages may interleave. [maxMessageSize] limits every
 * padded message, and each type limits its own messages, see [FrameType.maxSize].
 *
 * Every frame but the last of a message carries bytes, and the bytes of a message go into
 * one buffer that grows with them: what a partial message holds follows what it received,
 * and never more than its type, padded, allows.
 */
class Reassembler(
    private val maxMessageSize: Int = FrameCodec.MAX_MESSAGE_SIZE,
    private val maxInFlight: Int = FrameCodec.MAX_IN_FLIGHT,
) {
    /** The largest padded message of the type [typeCode]. */
    private fun paddedLimitOf(typeCode: Int): Int =
        minOf(maxMessageSize, MessagePadding.paddedSize(FrameType.maxSizeOf(typeCode)))

    private class Partial(val typeCode: Int) {
        var buffer = ByteArray(0)
        var size = 0
    }

    private val partials = LinkedHashMap<Int, Partial>()

    /** `true` while a message of the [type] is partly received: some of its frames arrived, not the last. */
    fun hasPartial(type: FrameType): Boolean = partials.values.any { it.typeCode == type.code }

    /** Adds [frame], returning the message it completes, if any. */
    fun accept(frame: Frame): ReassemblyResult {
        val partial = partials[frame.msgId]
        val error = errorOf(frame, partial)
        return if (error != null) {
            partials.remove(frame.msgId)
            ReassemblyResult.Failed(error)
        } else {
            append(partial, frame)
        }
    }

    /** Why [frame] can not join its message, of which [partial] arrived so far, if it can not. */
    private fun errorOf(frame: Frame, partial: Partial?): FrameError? = when {
        !frame.isFinal && frame.payload.isEmpty() -> FrameError.EMPTY_FRAME
        partial == null -> startErrorOf(frame)
        partial.typeCode != frame.typeCode -> FrameError.TYPE_CHANGED
        partial.size.toLong() + frame.payload.size > paddedLimitOf(frame.typeCode) -> FrameError.MESSAGE_TOO_LARGE
        else -> null
    }

    /** Why [frame], the first of its message, can not start it, if it can not. */
    private fun startErrorOf(frame: Frame): FrameError? = when {
        !frame.isFinal && partials.size >= maxInFlight -> FrameError.TOO_MANY_IN_FLIGHT
        frame.payload.size > paddedLimitOf(frame.typeCode) -> FrameError.MESSAGE_TOO_LARGE
        else -> null
    }

    private fun append(partial: Partial?, frame: Frame): ReassemblyResult {
        val target = partial ?: Partial(frame.typeCode).also { partials[frame.msgId] = it }
        val size = target.size + frame.payload.size
        if (size > target.buffer.size) {
            // Doubles, so the copies cost as much as the bytes; never past the largest message
            // of its type, nor past the padded size the message declares once that arrived.
            val declared = target
                .takeIf { it.size >= MessagePadding.HEADER_SIZE }
                ?.let { MessagePadding.declaredPaddedSize(it.buffer) }
                ?.takeIf { it >= size }
                ?: Long.MAX_VALUE
            val limit = minOf(declared, paddedLimitOf(target.typeCode).toLong()).toInt()
            val capacity = maxOf(size, minOf(target.buffer.size * 2, limit))
            target.buffer = target.buffer.copyOf(capacity)
        }
        frame.payload.copyInto(target.buffer, destinationOffset = target.size)
        target.size = size
        if (!frame.isFinal) return ReassemblyResult.Pending
        partials.remove(frame.msgId)
        val payload = MessagePadding.unpad(target.buffer, target.size)
        return when {
            payload == null -> ReassemblyResult.Failed(FrameError.INVALID_PADDING)
            // A padding bucket holds more than the type takes.
            payload.size > FrameType.maxSizeOf(frame.typeCode) -> ReassemblyResult.Failed(FrameError.MESSAGE_TOO_LARGE)
            else -> ReassemblyResult.Complete(Message(frame.typeCode, frame.msgId, payload))
        }
    }
}

/** Assigns message ids and splits outgoing messages into frames. */
class OutgoingMessages(
    private val maxPayload: Int = FrameCodec.MAX_PAYLOAD_SIZE,
) {
    private var nextId = 1

    /** Returns the frames of a new message of the [type]. */
    fun frames(type: FrameType, payload: ByteArray): List<Frame> =
        FrameCodec.split(type, msgId = nextId++, payload = payload, maxPayload = maxPayload)
}
