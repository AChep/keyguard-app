package com.artemchep.keyguard.util.foundation.io

import kotlinx.io.Sink
import kotlinx.io.Source

private const val BYTE_SNAPSHOT_TRANSFER_BYTES = 64 * 1024

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

/** Copies this snapshot without taking ownership of [output]. */
fun ByteSnapshot.copyTo(output: Sink) {
    openSource().use { input ->
        input.consumeWithErasedBuffer(
            bufferSize = BYTE_SNAPSHOT_TRANSFER_BYTES,
        ) { transfer, length ->
            output.write(transfer, 0, length)
        }
    }
}

/**
 * Runs [write] against this store's sink, then seals the store and returns the
 * snapshot. The store is always closed, even if [write] or the seal fails; the
 * caller owns the returned snapshot and must close it.
 */
inline fun ByteStoreWriter.buildSnapshot(write: (Sink) -> Unit): ByteSnapshot =
    use { writer ->
        writer.sink().use(write)
        writer.seal()
    }

/**
 * Runs [write] against this store's sink and, once it completes, copies the
 * staged bytes to [output]. Returns [write]'s result. [output] is flushed but
 * not closed; the store and snapshot are always released.
 */
inline fun <T> ByteStoreWriter.stageTo(
    output: Sink,
    write: (Sink) -> T,
): T = use { writer ->
    val result = writer.sink().use(write)
    writer.seal().use { snapshot ->
        snapshot.copyTo(output)
    }
    output.flush()
    result
}
