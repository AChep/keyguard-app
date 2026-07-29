package com.artemchep.keyguard.common.service.staging

import com.artemchep.keyguard.util.io.spool.ByteStoreWriter

/**
 * Stable, non-sensitive identity of bytes held in provisional staging.
 *
 * The production factory maps each purpose to its required spill protection.
 * Callers deliberately cannot select the backing directory or downgrade that
 * protection.
 */
internal enum class StagingPurpose {
    FileCiphertext,
    DownloadSinkPlaintext,
    PendingUploadPlaintext,
    OpenPgpPlaintext,
    KeePassDatabase,
}

/**
 * Operation-specific adaptive-spool limits.
 *
 * These values remain owned by the domain that understands why a limit
 * exists; the staging factory owns only validation and secure construction.
 */
internal data class SpoolLimits(
    val memoryBytes: Long,
    val maximumBytes: Long,
) {
    init {
        require(memoryBytes >= 0L) {
            "Staging spool memory limit must not be negative"
        }
        require(maximumBytes >= memoryBytes) {
            "Staging spool maximum must be greater than or equal to its memory limit"
        }
    }
}

internal interface StagingSpoolFactory {
    fun create(
        purpose: StagingPurpose,
        limits: SpoolLimits,
        limitExceeded: (maximumBytes: Long) -> Throwable,
    ): ByteStoreWriter
}
