package com.artemchep.keyguard.provider.bitwarden.upload

import kotlin.test.assertContentEquals
import kotlin.time.Instant

/**
 * Asserts that borrowed key material was zeroed once its operation finished.
 */
internal fun assertKeyCleared(
    key: ByteArray,
) = assertContentEquals(ByteArray(key.size), key)

internal fun pendingUploadFile(
    path: String,
) = PendingUploadFile(
    path = path,
    plainSize = 123L,
    encryptedSize = 321L,
)

internal data class SweepCall(
    val accountId: String,
    val namespace: String,
    val referencedPaths: Set<String>,
    val olderThan: Instant,
)

/**
 * Coordinator that hands out [stagedUploads] in order, recording each staging
 * call and the key buffer it was given so a test can assert the key was
 * cleared afterwards.
 */
internal class StagingPendingUploadCoordinator(
    stagedUploads: List<PendingUploadFile> = emptyList(),
    private val stageFailure: Throwable? = null,
) : PendingUploadCoordinator by FailingPendingUploadCoordinator {
    private val stagedUploads = ArrayDeque(stagedUploads)

    data class StageCall(
        val target: PendingUploadTarget,
        val sourceUri: String,
        val fileKey: String,
    )

    val stageCalls = mutableListOf<StageCall>()
    val stageFileKeyRefs = mutableListOf<ByteArray>()
    val deleteCalls = mutableListOf<PendingUploadFile>()

    override suspend fun stage(
        target: PendingUploadTarget,
        sourceUri: String,
        fileKey: ByteArray,
    ): PendingUploadFile {
        stageCalls += StageCall(
            target = target,
            sourceUri = sourceUri,
            fileKey = fileKey.decodeToString(),
        )
        stageFileKeyRefs += fileKey
        stageFailure?.let { failure -> throw failure }
        return stagedUploads.removeFirstOrNull()
            ?: error("No staged upload prepared for test")
    }

    override suspend fun delete(
        pendingUpload: PendingUploadFile,
    ) {
        deleteCalls += pendingUpload
    }
}

/**
 * Test staging service whose operations fail unless a test overrides the
 * behavior it expects to exercise.
 */
internal object FailingEncryptedFilePendingUploadService : EncryptedFilePendingUploadService {
    override suspend fun stage(
        accountId: String,
        namespace: String,
        fileId: String,
        sourceUri: String,
        fileKey: ByteArray,
    ): PendingUploadFile = unexpectedCall("stage")

    override suspend fun readPlaintext(
        pendingUpload: PendingUploadFile,
        fileKey: ByteArray,
    ): ByteArray = unexpectedCall("readPlaintext")

    override suspend fun markUploaded(
        pendingUpload: PendingUploadFile,
    ): Unit = unexpectedCall("markUploaded")

    override suspend fun isUploaded(
        pendingUpload: PendingUploadFile,
    ): Boolean = unexpectedCall("isUploaded")

    override suspend fun delete(
        pendingUpload: PendingUploadFile,
    ): Unit = unexpectedCall("delete")

    override suspend fun sweepOrphans(
        accountId: String,
        namespace: String,
        referencedPaths: Set<String>,
        olderThan: Instant,
    ): Unit = unexpectedCall("sweepOrphans")

    private fun unexpectedCall(
        operation: String,
    ): Nothing = error("Unexpected pending-upload staging call: $operation")
}

/**
 * Records orphan sweeps, optionally failing the sweep of a given namespace.
 */
internal class SweepRecordingEncryptedFilePendingUploadService(
    private val sweepFailures: Map<String, Throwable> = emptyMap(),
) : EncryptedFilePendingUploadService by FailingEncryptedFilePendingUploadService {
    val sweepCalls = mutableListOf<SweepCall>()

    override suspend fun sweepOrphans(
        accountId: String,
        namespace: String,
        referencedPaths: Set<String>,
        olderThan: Instant,
    ) {
        sweepCalls += SweepCall(
            accountId = accountId,
            namespace = namespace,
            referencedPaths = referencedPaths,
            olderThan = olderThan,
        )
        sweepFailures[namespace]?.let { failure -> throw failure }
    }
}
