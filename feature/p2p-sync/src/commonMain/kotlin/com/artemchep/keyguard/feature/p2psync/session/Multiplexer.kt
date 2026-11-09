package com.artemchep.keyguard.feature.p2psync.session

/**
 * Orders outgoing frames of one stream. Control frames always go first. Sync and blob
 * frames share the rest by weighted round robin, [SYNC_WEIGHT] sync frames for every
 * [BLOB_WEIGHT] blob frames, so a large attachment never stalls an envelope. Frames of one
 * channel keep their order.
 */
class Multiplexer {
    private val queues = FrameChannel.entries.associateWith { ArrayDeque<Frame>() }
    private var slot = 0

    /** `true` if no frame is queued. */
    val isEmpty: Boolean get() = queues.values.all { it.isEmpty() }

    /** The payload bytes of the queued frames: what a peer that stops reading costs. */
    var queuedBytes: Long = 0L
        private set

    /** Queues [frame]; frames of unknown types go with control frames. */
    fun enqueue(frame: Frame) {
        queue(frame.type?.channel ?: FrameChannel.CONTROL).addLast(frame)
        queuedBytes += frame.payload.size
    }

    /** Queues every frame of [frames], in order. */
    fun enqueueAll(frames: Iterable<Frame>) {
        frames.forEach(::enqueue)
    }

    /** Returns the next frame to send, or `null` if none is queued. */
    fun next(): Frame? {
        val control = queue(FrameChannel.CONTROL).removeFirstOrNull()
        val frame = control ?: nextWeighted()
        if (frame != null) queuedBytes -= frame.payload.size
        return frame
    }

    private fun nextWeighted(): Frame? {
        val preferred = if (slot < SYNC_WEIGHT) FrameChannel.SYNC else FrameChannel.BLOB
        val other = if (preferred == FrameChannel.SYNC) FrameChannel.BLOB else FrameChannel.SYNC
        val frame = queue(preferred).removeFirstOrNull() ?: queue(other).removeFirstOrNull()
        if (frame != null) slot = (slot + 1) % (SYNC_WEIGHT + BLOB_WEIGHT)
        return frame
    }

    private fun queue(channel: FrameChannel): ArrayDeque<Frame> = queues.getValue(channel)

    private companion object {
        /** Sync frames per round. */
        const val SYNC_WEIGHT: Int = 2

        /** Blob frames per round. */
        const val BLOB_WEIGHT: Int = 1
    }
}
