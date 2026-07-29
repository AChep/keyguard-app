package com.artemchep.keyguard.util.io.spool

import kotlinx.io.Sink
import kotlinx.io.Source

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
