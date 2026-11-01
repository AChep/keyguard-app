package com.artemchep.keyguard.common.service.download

import com.artemchep.keyguard.common.service.staging.SpoolLimits
import com.artemchep.keyguard.common.service.staging.StagingPurpose
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.util.io.spool.ByteSnapshot
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.io.IOException
import kotlinx.io.Sink

/**
 * Captures verified content without publishing a filesystem path. Owns the
 * provisional content until its producer has completed every validation step.
 */
internal class DownloadSnapshotWriter(
    private val stagingSpoolFactory: StagingSpoolFactory,
    private val limits: SpoolLimits = SpoolLimits(
        memoryBytes = DOWNLOAD_PLAINTEXT_MEMORY_LIMIT_BYTES,
        maximumBytes = DOWNLOAD_PLAINTEXT_MAXIMUM_BYTES,
    ),
) : DownloadWriter, AutoCloseable {
    private val lock = SynchronizedObject()
    private var started = false
    private var closed = false
    private var snapshot: ByteSnapshot? = null

    // Both arbitrary producer failures and errors while sealing must discard the snapshot.
    @Suppress("TooGenericExceptionCaught")
    fun capture(
        checkCancellation: () -> Unit = {},
        write: (Sink, () -> Unit) -> Unit,
    ) {
        synchronized(lock) {
            check(!closed) { "Snapshot destination is closed" }
            check(!started) { "Snapshot destination has already been written" }
            started = true
        }
        // Sealed snapshots do not retain this probe, so a taken snapshot never keeps the preparation job reachable.
        val checkActive = {
            synchronized(lock) { check(!closed) { "Snapshot destination is closed" } }
            checkCancellation()
        }
        var unpublished: ByteSnapshot? = null
        try {
            checkActive()
            stagingSpoolFactory.create(
                purpose = StagingPurpose.PreparedContentPlaintext,
                limits = limits,
                checkCancellation = checkActive,
                limitExceeded = { limit ->
                    IOException("Downloaded plaintext exceeds the staging limit of $limit bytes")
                },
            ).use { spool ->
                spool.sink().use { sink -> write(sink, checkActive) }
                checkActive()
                unpublished = spool.seal()
            }
            checkActive()
            synchronized(lock) {
                check(!closed) { "Snapshot destination is closed" }
                snapshot = requireNotNull(unpublished)
                unpublished = null
            }
        } catch (failure: Throwable) {
            unpublished?.let { value ->
                runCatching { value.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
    }

    /** Transfers the completed snapshot to the caller exactly once. */
    fun takeSnapshot(): ByteSnapshot = synchronized(lock) {
        check(!closed) { "Snapshot destination is closed" }
        checkNotNull(snapshot) { "Snapshot is not ready or has already been taken" }.also { snapshot = null }
    }

    override fun close() {
        val abandoned = synchronized(lock) {
            if (closed) return
            closed = true
            snapshot.also { snapshot = null }
        }
        abandoned?.close()
    }
}
