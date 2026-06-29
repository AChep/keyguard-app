package com.artemchep.keyguard.common.service.keepass.storage

import com.artemchep.keyguard.common.service.keepass.StagedDatabase
import kotlinx.io.Source

internal interface KeePassDatabaseStorage {
    suspend fun exists(): Boolean

    suspend fun stat(): KeePassDatabaseMetadata?

    suspend fun read(): Source

    /**
     * Installs the already-verified [staged] bytes at the destination,
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
