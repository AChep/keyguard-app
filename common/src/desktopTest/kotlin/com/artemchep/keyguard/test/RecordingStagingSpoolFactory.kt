package com.artemchep.keyguard.test

import com.artemchep.keyguard.common.service.staging.SpoolLimits
import com.artemchep.keyguard.common.service.staging.StagingPurpose
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.util.io.spool.ByteSnapshot
import com.artemchep.keyguard.util.io.spool.ByteStoreWriter
import com.artemchep.keyguard.util.io.spool.RandomAccessByteSnapshot
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Delegates staging and records every purpose and every sealed random-access snapshot,
 * whose closes it counts. [onSnapshotSealed] runs on the sealing thread.
 */
internal class RecordingStagingSpoolFactory(
    private val delegate: StagingSpoolFactory,
    @Volatile var onSnapshotSealed: () -> Unit = {},
) : StagingSpoolFactory {
    val purposes: MutableList<StagingPurpose> = Collections.synchronizedList(mutableListOf())
    val snapshots = ConcurrentLinkedQueue<RecordingSnapshot>()

    override fun create(
        purpose: StagingPurpose,
        limits: SpoolLimits,
        checkCancellation: () -> Unit,
        limitExceeded: (maximumBytes: Long) -> Throwable,
    ): ByteStoreWriter {
        purposes += purpose
        val writer = delegate.create(purpose, limits, checkCancellation, limitExceeded)
        return object : ByteStoreWriter by writer {
            override fun seal(): ByteSnapshot = when (val snapshot = writer.seal()) {
                is RandomAccessByteSnapshot -> RecordingSnapshot(snapshot).also {
                    snapshots.add(it)
                    onSnapshotSealed()
                }

                else -> snapshot
            }
        }
    }

    class RecordingSnapshot(
        private val delegate: RandomAccessByteSnapshot,
    ) : RandomAccessByteSnapshot by delegate {
        val closeCount = AtomicInteger()

        override fun close() {
            closeCount.incrementAndGet()
            delegate.close()
        }
    }
}
