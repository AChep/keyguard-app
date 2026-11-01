package com.artemchep.keyguard.util.io.spool

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered

/**
 * An immutable, replayable byte stream.
 *
 * Every [openSource] call returns a new cursor positioned at byte zero. Sources must be closed
 * before the snapshot itself is closed.
 */
interface ByteSnapshot : AutoCloseable {
    /** Number of logical bytes returned by [openSource]. */
    val size: Long

    fun openSource(): Source
}

/**
 * A [ByteSnapshot] that serves arbitrary offsets without replaying its bytes from zero.
 *
 * Readers are independent cursors and may run on different threads. They must be closed
 * before the snapshot itself is closed.
 */
interface RandomAccessByteSnapshot : ByteSnapshot {
    fun openReader(): ByteSnapshotReader

    /** Replays the bytes through one [openReader]; the source owns that reader. */
    override fun openSource(): Source = ReaderRawSource(openReader()).buffered()
}

/** A positional reader over one [RandomAccessByteSnapshot]; a single reader is not thread-safe. */
interface ByteSnapshotReader : AutoCloseable {
    /**
     * Fills `data[startIndex, endIndex)` from [position], stopping only at the snapshot's
     * end. Returns the number of bytes read; zero means an empty range or a position at or
     * beyond the end.
     */
    fun readAt(
        position: Long,
        data: ByteArray,
        startIndex: Int = 0,
        endIndex: Int = data.size,
    ): Int
}

/**
 * A single-writer byte store which transfers ownership to a [ByteSnapshot] when sealed.
 *
 * Closing a writer without sealing it abandons the store: implementations release their
 * resources, including the claimed sink, and may discard bytes still buffered in the sink
 * rather than flushing them.
 */
interface ByteStoreWriter : AutoCloseable {
    /** Returns the only writable view of this store. */
    fun sink(): Sink

    /**
     * Finishes writing and transfers ownership of the stored bytes to the returned snapshot.
     * Closing this writer after a successful seal does not close the snapshot.
     */
    fun seal(): ByteSnapshot
}

fun interface ByteStoreFactory {
    fun create(): ByteStoreWriter
}

/** Reads [reader] from byte zero; the transfer buffer never keeps bytes between calls. */
private class ReaderRawSource(
    private val reader: ByteSnapshotReader,
) : RawSource {
    private val transfer = ByteArray(BYTE_SNAPSHOT_TRANSFER_BYTES)
    private var position = 0L

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L) { "Invalid snapshot read size" }
        if (byteCount == 0L) return 0L
        val requested = minOf(byteCount, transfer.size.toLong()).toInt()
        return try {
            val read = reader.readAt(position, transfer, 0, requested)
            sink.write(transfer, 0, read)
            position += read
            if (read == 0) -1L else read.toLong()
        } finally {
            transfer.fill(0)
        }
    }

    override fun close() {
        transfer.fill(0)
        reader.close()
    }
}
