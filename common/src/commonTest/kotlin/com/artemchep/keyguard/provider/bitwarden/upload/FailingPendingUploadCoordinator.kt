package com.artemchep.keyguard.provider.bitwarden.upload

import kotlin.time.Instant

/**
 * Test coordinator whose operations fail unless a test overrides the behavior
 * it expects to exercise.
 */
internal object FailingPendingUploadCoordinator : PendingUploadCoordinator {
    override suspend fun stage(
        target: PendingUploadTarget,
        sourceUri: String,
        fileKey: ByteArray,
    ): PendingUploadFile = unexpectedCall("stage")

    override suspend fun readPlaintext(
        pendingUpload: PendingUploadFile,
        fileKey: ByteArray,
    ): ByteArray = unexpectedCall("readPlaintext")

    override suspend fun delete(
        pendingUpload: PendingUploadFile,
    ): Unit = unexpectedCall("delete")

    override suspend fun markUploaded(
        pendingUpload: PendingUploadFile,
    ): Unit = unexpectedCall("markUploaded")

    override suspend fun isUploaded(
        pendingUpload: PendingUploadFile,
    ): Boolean = unexpectedCall("isUploaded")

    override suspend fun sweepOrphans(
        accountId: String,
        namespace: String,
        referencedPaths: Set<String>,
        olderThan: Instant,
    ): Unit = unexpectedCall("sweepOrphans")

    override suspend fun <T> persist(
        createdPendingUploads: Collection<PendingUploadFile>,
        removedPendingUploads: Collection<PendingUploadFile>,
        block: suspend () -> T,
    ): T = unexpectedCall("persist")

    private fun unexpectedCall(
        operation: String,
    ): Nothing = error("Unexpected pending-upload coordinator call: $operation")
}
