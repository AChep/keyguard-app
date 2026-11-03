package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig

/**
 * Reads a repository of Keyguard's automatic backups as an import source:
 * opens it, lists its snapshots, and parses one into an [ImportPlan].
 *
 * Like [ImportService], a boundary for untrusted input: anyone with access to
 * the backup location could have changed its files.
 */
interface BackupImportService {
    /**
     * Opens the repository at [store]. [password] is the backup password;
     * without one an encrypted repository fails with
     * [ImportError.PasswordRequired]. The source lists the repository's
     * snapshots, never none; the caller closes a source it got back.
     */
    suspend fun open(
        store: BackupStoreConfig,
        password: Password?,
    ): ImportOpenResult
}

/**
 * A failure of a backup location. A wrong password or a damaged backup is an
 * [ImportError], like that of any other source.
 */
sealed interface BackupImportError : ImportOpenError {
    /**
     * The location holds no Keyguard backup.
     */
    data object NotFound : BackupImportError

    /**
     * The location rejected the credentials or denied access.
     */
    data object AccessDenied : BackupImportError

    /**
     * The location could not be reached.
     */
    data object Unreachable : BackupImportError

    /**
     * The backup holds no snapshot.
     */
    data object Empty : BackupImportError
}
