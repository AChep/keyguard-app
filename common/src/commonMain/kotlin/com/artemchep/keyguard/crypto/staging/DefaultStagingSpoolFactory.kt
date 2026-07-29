package com.artemchep.keyguard.crypto.staging

import com.artemchep.keyguard.common.service.staging.SpoolLimits
import com.artemchep.keyguard.common.service.staging.StagingPurpose
import com.artemchep.keyguard.common.service.staging.StagingSpoolBacking
import com.artemchep.keyguard.common.service.staging.StagingSpoolEvent
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.common.service.staging.StagingSpoolObserver
import com.artemchep.keyguard.common.service.staging.StagingSpoolOutcome
import com.artemchep.keyguard.crypto.EncryptedTemporarySpillStorage
import com.artemchep.keyguard.crypto.PrivateTemporaryStorage
import com.artemchep.keyguard.crypto.createPrivateTemporaryStorage
import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import com.artemchep.keyguard.util.io.scratch.PrivateTemporarySpillStorage
import com.artemchep.keyguard.util.io.spool.AdaptiveSpool
import com.artemchep.keyguard.util.io.spool.ByteSnapshot
import com.artemchep.keyguard.util.io.spool.ByteStoreFactory
import com.artemchep.keyguard.util.io.spool.ByteStoreWriter
import kotlinx.io.Sink

/**
 * Builds adaptive staging storage with protection fixed by [StagingPurpose].
 *
 * Confidential purposes fail closed if encrypted spill cannot be created;
 * they never fall back to raw scratch or unbounded memory.
 */
@OptIn(InternalKeyguardIoApi::class)
internal class DefaultStagingSpoolFactory private constructor(
    private val scratchStorageFactory: () -> PrivateTemporaryStorage,
    private val observer: StagingSpoolObserver,
) : StagingSpoolFactory {
    constructor() : this(
        scratchStorageFactory = ::createPrivateTemporaryStorage,
        observer = StagingSpoolObserver.NoOp,
    )

    constructor(
        observer: StagingSpoolObserver,
    ) : this(
        scratchStorageFactory = ::createPrivateTemporaryStorage,
        observer = observer,
    )

    override fun create(
        purpose: StagingPurpose,
        limits: SpoolLimits,
        limitExceeded: (maximumBytes: Long) -> Throwable,
    ): ByteStoreWriter {
        val spool = AdaptiveSpool(
            memoryLimitBytes = limits.memoryBytes,
            maximumBytes = limits.maximumBytes,
            spillFactory = purpose.spillFactory(),
            limitExceeded = limitExceeded,
        )
        return ObservedStagingSpool(
            delegate = spool,
            purpose = purpose,
            observer = observer,
        )
    }

    private fun StagingPurpose.spillFactory(): ByteStoreFactory = when (this) {
        StagingPurpose.FileCiphertext,
        StagingPurpose.KeePassDatabase,
        -> ByteStoreFactory {
            PrivateTemporarySpillStorage(scratchStorageFactory())
        }

        StagingPurpose.DownloadSinkPlaintext,
        StagingPurpose.PendingUploadPlaintext,
        StagingPurpose.OpenPgpPlaintext,
        -> ByteStoreFactory {
            EncryptedTemporarySpillStorage.create(scratchStorageFactory())
        }
    }

    internal companion object {
        fun forTesting(
            scratchStorageFactory: () -> PrivateTemporaryStorage,
            observer: StagingSpoolObserver = StagingSpoolObserver.NoOp,
        ): DefaultStagingSpoolFactory = DefaultStagingSpoolFactory(
            scratchStorageFactory = scratchStorageFactory,
            observer = observer,
        )
    }
}

@Suppress("TooGenericExceptionCaught")
private class ObservedStagingSpool(
    private val delegate: AdaptiveSpool,
    private val purpose: StagingPurpose,
    private val observer: StagingSpoolObserver,
) : ByteStoreWriter {
    private var reported = false

    override fun sink(): Sink = observeFailure {
        delegate.sink()
    }

    override fun seal(): ByteSnapshot = try {
        delegate.seal().also {
            report(StagingSpoolOutcome.Sealed)
        }
    } catch (failure: Throwable) {
        report(StagingSpoolOutcome.Failed)
        throw failure
    }

    override fun close() {
        try {
            delegate.close()
        } catch (failure: Throwable) {
            report(StagingSpoolOutcome.Failed)
            throw failure
        } finally {
            report(StagingSpoolOutcome.Abandoned)
        }
    }

    private inline fun <T> observeFailure(
        block: () -> T,
    ): T = try {
        block()
    } catch (failure: Throwable) {
        report(StagingSpoolOutcome.Failed)
        throw failure
    }

    private fun report(
        outcome: StagingSpoolOutcome,
    ) {
        if (reported) return
        reported = true
        runCatching {
            observer.onEvent(
                StagingSpoolEvent(
                    purpose = purpose,
                    outcome = outcome,
                    backing = if (delegate.spilled) {
                        StagingSpoolBacking.Spill
                    } else {
                        StagingSpoolBacking.Memory
                    },
                ),
            )
        }
    }
}
