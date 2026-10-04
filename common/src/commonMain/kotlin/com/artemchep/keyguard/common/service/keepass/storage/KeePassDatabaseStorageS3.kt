package com.artemchep.keyguard.common.service.keepass.storage

import com.artemchep.keyguard.common.exception.KeePassDatabaseModifiedExternallyException
import com.artemchep.keyguard.common.exception.KeePassFileAlreadyExistsException
import com.artemchep.keyguard.common.service.keepass.StagedDatabase
import com.artemchep.keyguard.common.service.s3.S3ClientFactory
import com.artemchep.keyguard.util.s3.S3Client
import com.artemchep.keyguard.util.s3.S3ClientConfig
import com.artemchep.keyguard.util.s3.S3Exception
import com.artemchep.keyguard.util.s3.S3Object
import com.artemchep.keyguard.util.s3.S3WritePrecondition
import kotlinx.io.Source

internal class KeePassDatabaseStorageS3(
    private val config: S3ClientConfig,
    private val key: String,
    private val s3ClientFactory: S3ClientFactory,
) : KeePassDatabaseStorage {
    override val decodeReadAttempts: Int = 2

    override fun isRetryableReadFailure(e: Exception): Boolean =
        e is S3Exception && e.retryable

    private var client: S3Client? = null

    override suspend fun exists(): Boolean = stat() != null

    override suspend fun stat(): KeePassDatabaseMetadata? = client()
        .headObject(key)
        ?.toKeePassDatabaseMetadata()

    override suspend fun read(): Source = client().getObject(key)

    override suspend fun publish(
        mode: KeePassDatabaseWriteMode,
        staged: StagedDatabase,
        expected: KeePassDatabaseMetadata?,
    ): KeePassDatabaseMetadata? {
        // A single PUT replaces the object atomically; the conditional
        // headers protect against overwriting a concurrent change.
        val precondition = when (mode) {
            KeePassDatabaseWriteMode.Create -> S3WritePrecondition.IfNoneMatch
            KeePassDatabaseWriteMode.CreateOrReplace -> expected
                ?.etag
                ?.takeUnless { it.isBlank() }
                ?.let(S3WritePrecondition::IfMatch)
        }
        return try {
            client().putObject(
                key = key,
                contentLength = staged.size,
                precondition = precondition,
                body = staged::replayTo,
            ).toKeePassDatabaseMetadata()
        } catch (e: S3Exception) {
            throw e.toPublishException(mode)
        }
    }

    private fun S3Exception.toPublishException(
        mode: KeePassDatabaseWriteMode,
    ): Exception = when {
        this is S3Exception.PreconditionFailed -> KeePassDatabaseModifiedExternallyException(
            message = "KeePass database was modified externally while publishing.",
            cause = this,
        )

        this is S3Exception.AlreadyExists && mode == KeePassDatabaseWriteMode.Create ->
            KeePassFileAlreadyExistsException(this)

        else -> this
    }

    private suspend fun client(): S3Client {
        client?.let { return it }
        val created = s3ClientFactory.create(config)
        client = created
        return created
    }
}

private fun S3Object.toKeePassDatabaseMetadata(): KeePassDatabaseMetadata =
    KeePassDatabaseMetadata(
        etag = etag,
        lastModified = lastModified,
        size = size,
    )
