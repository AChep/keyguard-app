package com.artemchep.keyguard.common.service.dragout

import com.artemchep.keyguard.util.io.spool.ByteSnapshotReader

/**
 * Fully validated bytes owned by one drag offer.
 *
 * There is no shared file URI. Every [openReader] is an independent cursor that
 * retains the backing until that reader closes. Readers perform blocking I/O and
 * belong on a worker thread.
 */
// Independent lease callbacks keep storage and platform ownership out of this public capability.
class PreparedContent internal constructor(
    val name: String,
    val size: Long,
    private val onOpenReader: () -> PreparedContentReader,
    private val isOffered: () -> Boolean,
    private val onHandoff: () -> Unit,
    private val onClose: () -> Unit,
    private val onRetirement: (() -> Unit) -> AutoCloseable = { AutoCloseable {} },
) : AutoCloseable {
    /** Acquires a reader lease without opening or authenticating the backing on the calling thread. */
    fun openReader(): PreparedContentReader = onOpenReader()

    /** Checks the offer's deadline without opening the backing or consuming a reader slot. */
    fun isAvailable(): Boolean = isOffered()

    /** Extends the offered capability's lifetime once when handing it to the OS. */
    fun handoff() = onHandoff()

    /**
     * Runs [action] once when the offer stops accepting readers, by expiry or [close], or
     * immediately if it already has. Transports release their platform registrations here
     * instead of keeping their own deadlines. Closing the returned handle unsubscribes.
     */
    fun invokeOnRetirement(action: () -> Unit): AutoCloseable = onRetirement(action)

    /** Rejects future readers; readers already opened may finish independently. */
    override fun close() = onClose()
}

/**
 * An independent cursor over prepared bytes. Calls are serialized, so a close
 * waits for an in-flight read.
 *
 * Reads are blocking worker I/O. Any offset costs at most one authenticated
 * chunk decryption; memory stays bounded to one chunk per reader.
 */
interface PreparedContentReader : ByteSnapshotReader
