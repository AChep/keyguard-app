package com.artemchep.keyguard.test

import com.artemchep.keyguard.common.service.staging.SpoolLimits
import com.artemchep.keyguard.common.service.staging.StagingPurpose
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.util.io.spool.AdaptiveSpool
import com.artemchep.keyguard.util.io.spool.ByteStoreWriter

/** Stages everything in memory up to the maximum and records each purpose; spilling fails. */
internal class MemoryStagingSpoolFactory : StagingSpoolFactory {
    val purposes = mutableListOf<StagingPurpose>()

    override fun create(
        purpose: StagingPurpose,
        limits: SpoolLimits,
        checkCancellation: () -> Unit,
        limitExceeded: (maximumBytes: Long) -> Throwable,
    ): ByteStoreWriter {
        purposes += purpose
        return AdaptiveSpool(
            memoryLimitBytes = limits.maximumBytes,
            maximumBytes = limits.maximumBytes,
            spillFactory = { error("Tests never spill.") },
            limitExceeded = limitExceeded,
        )
    }
}
