package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.exception.S3ConditionalWritesUnsupportedException
import com.artemchep.keyguard.common.service.s3.S3ClientFactory
import com.artemchep.keyguard.common.service.s3.joinS3Key
import com.artemchep.keyguard.common.service.s3.requireConditionalWrites
import com.artemchep.keyguard.common.service.s3.stripS3Prefix
import com.artemchep.keyguard.common.service.s3.toS3ClientConfig
import com.artemchep.keyguard.common.service.s3.toS3LocationOrNull
import com.artemchep.keyguard.common.service.staging.SpoolLimits
import com.artemchep.keyguard.common.service.staging.StagingPurpose
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.util.io.spool.copyTo
import com.artemchep.keyguard.util.s3.S3ByteRange
import com.artemchep.keyguard.util.s3.S3Client
import com.artemchep.keyguard.util.s3.S3Exception
import com.artemchep.keyguard.util.s3.S3Object
import com.artemchep.keyguard.util.s3.S3WritePrecondition
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered

/** Stores backup objects under [prefix] in an S3 bucket. */
class S3BackupObjectStore internal constructor(
    private val client: S3Client,
    private val prefix: String,
    private val stagingSpoolFactory: StagingSpoolFactory,
) : BackupObjectStore {
    override val capabilities: BackupObjectStoreCapabilities = BackupObjectStoreCapabilities(
        atomicWholeObjectWrite = true,
        atomicReplace = true,
        rangeRead = true,
        strongReadAfterWrite = true,
        strongListAfterWrite = true,
    )

    override suspend fun stat(
        key: BackupObjectKey,
    ): BackupObjectInfo? = translate(
        operation = BackupObjectStoreOperation.Stat,
        key = key,
    ) {
        client.headObject(joinS3Key(prefix, key.value))
            ?.toBackupObjectInfo(key)
    }

    override suspend fun read(
        key: BackupObjectKey,
        range: BackupByteRange?,
    ): Source = translate(
        operation = BackupObjectStoreOperation.Read,
        key = key,
        range = range,
    ) {
        client.getObject(
            key = joinS3Key(prefix, key.value),
            range = range?.let { S3ByteRange(offset = it.offset, length = it.length) },
        ).translateReadSource(
            operation = BackupObjectStoreOperation.Read,
            key = key,
            range = range,
        )
    }

    override suspend fun write(
        key: BackupObjectKey,
        mode: BackupWriteMode,
        write: suspend (Sink) -> Unit,
    ): BackupObjectInfo = translate(
        operation = BackupObjectStoreOperation.Write,
        key = key,
    ) {
        // A single PUT needs the length and a replayable body up front, so
        // the object is staged first.
        val writer = stagingSpoolFactory.create(
            purpose = StagingPurpose.BackupObjectUpload,
            limits = SpoolLimits(
                memoryBytes = MAX_IN_MEMORY_UPLOAD_BYTES,
                maximumBytes = MAX_UPLOAD_BYTES,
            ),
            limitExceeded = { limit ->
                IOException("Backup object exceeds the S3 single-upload limit of $limit bytes")
            },
        )
        val snapshot = writer.use {
            writer.sink().use { sink ->
                write(sink)
            }
            writer.seal()
        }
        snapshot.use {
            client.putObject(
                key = joinS3Key(prefix, key.value),
                contentLength = snapshot.size,
                precondition = when (mode) {
                    BackupWriteMode.Create -> S3WritePrecondition.IfNoneMatch
                    BackupWriteMode.CreateOrReplace -> null
                },
            ) { sink ->
                snapshot.copyTo(sink)
            }.toBackupObjectInfo(key)
        }
    }

    override suspend fun list(
        prefix: BackupObjectKeyPrefix,
        cursor: BackupListCursor?,
    ): BackupObjectListPage = translate(
        operation = BackupObjectStoreOperation.List,
        key = null,
    ) {
        val page = client.listObjects(
            prefix = joinS3Key(this.prefix, prefix.value),
            continuationToken = cursor?.value,
        )
        BackupObjectListPage(
            items = page.objects
                .mapNotNull { entry ->
                    val relative = stripS3Prefix(this.prefix, entry.key)
                        ?.takeIf { it.isNotEmpty() && !it.endsWith('/') }
                        ?: return@mapNotNull null
                    // Keys written by other tools may not be valid backup keys.
                    val key = runCatching { BackupObjectKey(relative) }.getOrNull()
                        ?: return@mapNotNull null
                    entry.toBackupObjectInfo(key)
                },
            nextCursor = page.nextContinuationToken?.let(::BackupListCursor),
        )
    }

    override suspend fun delete(
        key: BackupObjectKey,
    ) {
        translate(
            operation = BackupObjectStoreOperation.Delete,
            key = key,
        ) {
            client.deleteObject(joinS3Key(prefix, key.value))
        }
    }

    override suspend fun test(): BackupObjectStoreTestResult {
        testConditionalWrites()
        return super.test()
    }

    /** The default test only creates objects, which a server may do while ignoring the condition. */
    private suspend fun testConditionalWrites() {
        val probeKey = createBackupObjectStoreTestKey()
        val key = joinS3Key(prefix, probeKey.value)
        val payload = CONDITIONAL_WRITES_TEST_PAYLOAD
        translate(
            operation = BackupObjectStoreOperation.Test,
            key = probeKey,
        ) {
            client.putObject(key, payload, S3WritePrecondition.IfNoneMatch)
            try {
                client.requireConditionalWrites(key, payload)
            } catch (e: S3ConditionalWritesUnsupportedException) {
                throw BackupObjectStoreException.VerificationFailed(
                    key = probeKey,
                    reason = "S3 server ignores conditional writes",
                    cause = e,
                )
            } finally {
                withContext(NonCancellable) {
                    runCatching { client.deleteObject(key) }
                }
            }
        }
    }

    override suspend fun close() {
        translate(
            operation = BackupObjectStoreOperation.Close,
            key = null,
        ) {
            client.close()
        }
    }

    private inline fun <T> translate(
        operation: BackupObjectStoreOperation,
        key: BackupObjectKey?,
        range: BackupByteRange? = null,
        block: () -> T,
    ): T = try {
        block()
    } catch (e: S3Exception) {
        throw e.toBackupObjectStoreException(operation, key, range)
    }

    private fun S3Object.toBackupObjectInfo(
        key: BackupObjectKey,
    ): BackupObjectInfo = BackupObjectInfo(
        key = key,
        size = size,
        updatedAt = lastModified,
    )

    private fun Source.translateReadSource(
        operation: BackupObjectStoreOperation,
        key: BackupObjectKey,
        range: BackupByteRange?,
    ): Source = object : TranslatingSource(this) {
        override fun <T> translate(
            block: () -> T,
        ): T = this@S3BackupObjectStore.translate(
            operation = operation,
            key = key,
            range = range,
            block = block,
        )
    }.buffered()

    private companion object {
        private const val MAX_IN_MEMORY_UPLOAD_BYTES = 8L * 1024L * 1024L

        private val CONDITIONAL_WRITES_TEST_PAYLOAD = "keyguard-object-store-test\n".encodeToByteArray()

        /** The largest object a single S3 PUT may upload. */
        private const val MAX_UPLOAD_BYTES = 5L * 1024L * 1024L * 1024L
    }
}

// An error that does not fit the request, such as a 416 to a request
// without a range, falls through to the generic mapping.
private fun S3Exception.toBackupObjectStoreException(
    operation: BackupObjectStoreOperation,
    key: BackupObjectKey?,
    range: BackupByteRange?,
): BackupObjectStoreException = when {
    this is S3Exception.NotFound && key != null -> BackupObjectStoreException.NotFound(
        key = key,
        operation = operation,
        cause = this,
    )

    this is S3Exception.AlreadyExists && key != null -> BackupObjectStoreException.AlreadyExists(
        key = key,
        cause = this,
    )

    this is S3Exception.InvalidRange && key != null && range != null -> BackupObjectStoreException.InvalidRange(
        key = key,
        range = range,
        cause = this,
    )

    this is S3Exception.AuthenticationFailed -> BackupObjectStoreException.AuthenticationFailed(
        operation = operation,
        cause = this,
    )

    retryable -> BackupObjectStoreException.Transient(
        operation = operation,
        key = key,
        cause = this,
    )

    else -> BackupObjectStoreException.PermissionDenied(
        operation = operation,
        key = key,
        cause = this,
    )
}

class S3BackupObjectStoreFactory internal constructor(
    private val s3ClientFactory: S3ClientFactory,
    private val stagingSpoolFactory: StagingSpoolFactory,
) : BackupObjectStoreFactory {
    override suspend fun open(
        store: BackupStoreConfig,
    ): BackupObjectStore {
        val s3Store = requireNotNull(store as? BackupStoreConfig.S3) {
            "Backup S3 store configuration is required."
        }
        val location = requireNotNull(s3Store.toS3LocationOrNull()) {
            "Backup S3 store is not configured."
        }
        val client = s3ClientFactory.create(location.toS3ClientConfig())
        var probed = false
        try {
            // Checks the credentials, the bucket and the region up front.
            client.listObjects(
                prefix = location.prefix,
                maxKeys = 1,
            )
            probed = true
        } catch (e: S3Exception) {
            throw e.toBackupObjectStoreException(
                operation = BackupObjectStoreOperation.Open,
                key = null,
                range = null,
            )
        } finally {
            if (!probed) {
                withContext(NonCancellable) {
                    runCatching { client.close() }
                }
            }
        }
        return S3BackupObjectStore(
            client = client,
            prefix = location.prefix,
            stagingSpoolFactory = stagingSpoolFactory,
        )
    }
}
