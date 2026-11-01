package com.artemchep.keyguard.common.service.imports

import kotlin.time.Instant

/**
 * An import source open for reading: an export file, or a repository of
 * Keyguard's backups. Reads the attachments of the plan it parsed last, and
 * holds whatever it reads from until [close].
 */
interface OpenedImportSource : ImportAttachmentSource {
    /**
     * The snapshots to choose from, newest first. Empty for a source that
     * holds just one, such as a file: [parse] reads it with a `null` id.
     */
    val snapshots: List<ImportSourceSnapshot>

    /**
     * Parses the snapshot [snapshotId], or the only one for `null`. A
     * password-protected file opened without its password fails with
     * [ImportError.PasswordRequired]. [now] stamps the planned create
     * requests.
     */
    suspend fun parse(
        snapshotId: String?,
        now: Instant,
    ): ImportResult

    suspend fun close()
}

data class ImportSourceSnapshot(
    val id: String,
    val createdAt: Instant,
    val itemCount: Int,
    val attachmentCount: Int,
)

sealed interface ImportOpenResult {
    data class Success(
        val source: OpenedImportSource,
    ) : ImportOpenResult

    data class Failure(
        val error: ImportOpenError,
    ) : ImportOpenResult
}

/**
 * Why a source failed to open or to parse: an [ImportError], which any source
 * may answer, or a [BackupImportError] of a backup location.
 */
sealed interface ImportOpenError
