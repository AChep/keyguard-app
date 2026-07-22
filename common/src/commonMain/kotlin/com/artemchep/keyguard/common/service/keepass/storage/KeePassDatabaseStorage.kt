package com.artemchep.keyguard.common.service.keepass.storage

import com.artemchep.keyguard.common.service.keepass.StagedDatabase
import kotlinx.io.Source

internal interface KeePassDatabaseStorage {
    /**
     * Number of complete read-and-decode attempts allowed for this storage.
     * Remote backends can briefly expose stale or incomplete representations;
     * local files remain single-attempt so persistent format errors are not
     * retried unnecessarily.
     */
    val decodeReadAttempts: Int
        get() = 1

    /**
     * Classifies a failure thrown while reading or streaming this storage's
     * bytes as retryable for another complete [read] attempt. Remote backends
     * override this for their transport failures; local files never benefit
     * from a retry.
     */
    fun isRetryableReadFailure(e: Exception): Boolean = false

    suspend fun exists(): Boolean

    suspend fun stat(): KeePassDatabaseMetadata?

    suspend fun read(): Source

    /**
     * Installs the already-verified [staged] stream at the destination,
     * atomically where the backend supports it.
     *
     * @return the destination metadata after publishing (best effort).
     */
    suspend fun publish(
        mode: KeePassDatabaseWriteMode,
        staged: StagedDatabase,
        expected: KeePassDatabaseMetadata? = null,
    ): KeePassDatabaseMetadata?
}
