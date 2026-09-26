package com.artemchep.keyguard.provider.bitwarden.upload

import kotlinx.io.Source
import kotlin.time.Instant

interface EncryptedFilePendingUploadService {
    suspend fun stage(
        accountId: String,
        namespace: String,
        fileId: String,
        sourceUri: String,
        fileKey: ByteArray,
    ): PendingUploadFile

    /**
     * Stages caller-owned plaintext without creating a temporary plaintext file.
     *
     * [source] is borrowed: implementations read it and leave closing it to the
     * caller.
     */
    suspend fun stage(
        accountId: String,
        namespace: String,
        fileId: String,
        source: Source,
        fileKey: ByteArray,
    ): PendingUploadFile = throw UnsupportedOperationException(
        "Staging pending uploads from a stream is not supported on this platform.",
    )

    suspend fun readPlaintext(
        pendingUpload: PendingUploadFile,
        fileKey: ByteArray,
    ): ByteArray = throw UnsupportedOperationException(
        "Reading pending uploads is not supported on this platform.",
    )

    suspend fun markUploaded(
        pendingUpload: PendingUploadFile,
    )

    suspend fun isUploaded(
        pendingUpload: PendingUploadFile,
    ): Boolean

    suspend fun delete(
        pendingUpload: PendingUploadFile,
    )

    /**
     * Deletes staged-file artifacts in one account namespace when their base
     * upload path is not referenced by local state and every sibling is older
     * than [olderThan].
     */
    suspend fun sweepOrphans(
        accountId: String,
        namespace: String,
        referencedPaths: Set<String>,
        olderThan: Instant,
    )
}
