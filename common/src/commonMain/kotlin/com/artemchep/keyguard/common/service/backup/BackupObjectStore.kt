package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.util.foundation.io.readByteArrayAndClose
import kotlin.jvm.JvmInline
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.write

@JvmInline
value class BackupObjectKey(
    val value: String,
) {
    init {
        require(value.isNotBlank()) {
            "Backup object key must not be blank."
        }
        require(value.isBackupObjectPathRelative()) {
            "Backup object key must be relative."
        }
        require('\\' !in value) {
            "Backup object key must use '/' as the path separator."
        }
        require(value.toBackupObjectPathParts().none { it.isUnsafeBackupObjectPathPart() }) {
            "Backup object key must not contain empty, current, parent, or Windows drive path segments."
        }
    }
}

@JvmInline
value class BackupObjectKeyPrefix(
    val value: String,
) {
    init {
        require(value.isBackupObjectPathRelative()) {
            "Backup object key prefix must be relative."
        }
        require('\\' !in value) {
            "Backup object key prefix must use '/' as the path separator."
        }
        require(value.toBackupObjectPathParts(allowTrailingSlash = true).none { it.isUnsafeBackupObjectPathPart() }) {
            "Backup object key prefix must not contain empty, current, parent, or Windows drive path segments."
        }
    }
}

private fun String.isBackupObjectPathRelative(): Boolean =
    !startsWith("/") &&
        !startsWith("\\") &&
        !hasWindowsDrivePrefix()

private fun String.toBackupObjectPathParts(
    allowTrailingSlash: Boolean = false,
): List<String> = when {
    isEmpty() -> emptyList()
    allowTrailingSlash && endsWith("/") -> split('/').dropLast(1)
    else -> split('/')
}

private fun String.isUnsafeBackupObjectPathPart(): Boolean =
    isEmpty() ||
        this == "." ||
        this == ".." ||
        hasWindowsDrivePrefix()

private fun String.hasWindowsDrivePrefix(): Boolean =
    length >= 2 && this[1] == ':' && this[0].isAsciiLetter()

private fun Char.isAsciiLetter(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z'

@JvmInline
value class BackupListCursor(
    val value: String,
)

data class BackupByteRange(
    val offset: Long,
    val length: Long? = null,
) {
    init {
        require(offset >= 0L) {
            "Backup object read offset must not be negative."
        }
        require(length == null || length > 0L) {
            "Backup object read length must be positive."
        }
    }
}

data class BackupObjectInfo(
    val key: BackupObjectKey,
    val size: Long?,
    val updatedAt: Instant?,
)

data class BackupObjectListPage(
    val items: List<BackupObjectInfo>,
    val nextCursor: BackupListCursor? = null,
)

enum class BackupWriteMode {
    Create,
    CreateOrReplace,
}

data class BackupObjectStoreCapabilities(
    val atomicWholeObjectWrite: Boolean,
    val atomicReplace: Boolean,
    val rangeRead: Boolean,
    val strongReadAfterWrite: Boolean,
    val strongListAfterWrite: Boolean,
)

data class BackupObjectStoreTestResult(
    val probeKey: BackupObjectKey,
    val bytesWritten: Long,
    val bytesRead: Long,
    val listed: Boolean,
    val deleted: Boolean,
    val rangeRead: Boolean?,
    val capabilities: BackupObjectStoreCapabilities,
)

enum class BackupObjectStoreOperation {
    Open,
    Stat,
    Read,
    Write,
    List,
    Delete,
    Test,
    Close,
}

sealed class BackupObjectStoreException(
    val operation: BackupObjectStoreOperation,
    val key: BackupObjectKey?,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class NotFound(
        key: BackupObjectKey,
        operation: BackupObjectStoreOperation = BackupObjectStoreOperation.Read,
        cause: Throwable? = null,
    ) : BackupObjectStoreException(
        operation = operation,
        key = key,
        retryable = false,
        message = "Backup object '${key.value}' was not found.",
        cause = cause,
    )

    class AlreadyExists(
        key: BackupObjectKey,
        cause: Throwable? = null,
    ) : BackupObjectStoreException(
        operation = BackupObjectStoreOperation.Write,
        key = key,
        retryable = false,
        message = "Backup object '${key.value}' already exists.",
        cause = cause,
    )

    class InvalidRange(
        key: BackupObjectKey,
        val range: BackupByteRange,
        cause: Throwable? = null,
    ) : BackupObjectStoreException(
        operation = BackupObjectStoreOperation.Read,
        key = key,
        retryable = false,
        message = "Backup object '${key.value}' does not contain requested range " +
                "${range.offset}:${range.length ?: "end"}.",
        cause = cause,
    )

    class PermissionDenied(
        operation: BackupObjectStoreOperation,
        key: BackupObjectKey? = null,
        cause: Throwable? = null,
    ) : BackupObjectStoreException(
        operation = operation,
        key = key,
        retryable = false,
        message = "Permission denied while performing backup object store operation '${operation.name}'.",
        cause = cause,
    )

    class AuthenticationFailed(
        operation: BackupObjectStoreOperation = BackupObjectStoreOperation.Open,
        cause: Throwable? = null,
    ) : BackupObjectStoreException(
        operation = operation,
        key = null,
        retryable = false,
        message = "Backup object store authentication failed.",
        cause = cause,
    )

    class Transient(
        operation: BackupObjectStoreOperation,
        key: BackupObjectKey? = null,
        cause: Throwable? = null,
    ) : BackupObjectStoreException(
        operation = operation,
        key = key,
        retryable = true,
        message = "Backup object store operation '${operation.name}' failed with a transient error.",
        cause = cause,
    )

    class VerificationFailed(
        key: BackupObjectKey? = null,
        val reason: String,
        cause: Throwable? = null,
    ) : BackupObjectStoreException(
        operation = BackupObjectStoreOperation.Test,
        key = key,
        retryable = false,
        message = "Backup object store verification failed: $reason",
        cause = cause,
    )
}

/**
 * Stores opaque backup objects behind relative keys.
 *
 * Backup format concerns, such as ZIP contents, snapshot manifests, indexes and
 * retention rules, belong above this boundary. Implementations should only
 * publish fully written objects; failed writes must not leave partial objects
 * visible at the final key.
 *
 * Implementations should throw [BackupObjectStoreException] for expected store
 * failures so callers can distinguish permanent failures from retryable ones.
 */
interface BackupObjectStore {
    /**
     * Backend guarantees that callers may use to choose consistency, range-read
     * and atomic-write behavior.
     *
     * Implementations must report these values truthfully. The default [test]
     * probe uses them to decide which consistency guarantees and optional
     * operations to verify.
     */
    val capabilities: BackupObjectStoreCapabilities

    /**
     * Returns metadata for the object at [key], or `null` when the key is absent
     * or resolves to a non-object entry such as a directory.
     *
     * [BackupObjectInfo.size] and [BackupObjectInfo.updatedAt] are best-effort
     * fields and may be `null` when the backing store cannot provide them. If
     * [BackupObjectInfo.size] is present, it must describe the exact stored byte
     * count.
     *
     * Expected store failures should be reported as [BackupObjectStoreException].
     */
    suspend fun stat(
        key: BackupObjectKey,
    ): BackupObjectInfo?

    /**
     * Opens a [Source] for the object at [key].
     *
     * The caller owns the returned source and must close it. With no [range],
     * the source must return the exact stored bytes. With a valid [range], the
     * source must return only the requested byte slice starting at
     * [BackupByteRange.offset] and continuing for [BackupByteRange.length] bytes,
     * or to the end of the object when the length is `null`.
     *
     * Missing objects should throw [BackupObjectStoreException.NotFound]. Ranges
     * outside the object, or any non-null [range] when [capabilities] reports
     * `rangeRead = false`, should throw [BackupObjectStoreException.InvalidRange].
     * Other expected store failures should be reported as
     * [BackupObjectStoreException].
     */
    suspend fun read(
        key: BackupObjectKey,
        range: BackupByteRange? = null,
    ): Source

    /**
     * Writes an object at [key] by streaming bytes into the supplied [Sink].
     *
     * The returned [BackupObjectInfo] describes the committed object. In
     * [BackupWriteMode.Create] mode, an existing object at [key] must fail with
     * [BackupObjectStoreException.AlreadyExists]. In
     * [BackupWriteMode.CreateOrReplace] mode, an existing object may be replaced.
     *
     * Failed writes must not expose partial bytes at the final [key]. Whether
     * successful publication and replacement are atomic is described by
     * [capabilities].
     *
     * Expected store failures should be reported as [BackupObjectStoreException].
     */
    suspend fun write(
        key: BackupObjectKey,
        mode: BackupWriteMode = BackupWriteMode.CreateOrReplace,
        write: suspend (Sink) -> Unit,
    ): BackupObjectInfo

    /**
     * Lists non-directory objects whose full key starts with [prefix].
     *
     * Results should be returned in stable key-ascending order. When the result
     * is paginated, callers pass [BackupObjectListPage.nextCursor] back as
     * [cursor] until a page returns `nextCursor = null`.
     *
     * Expected store failures should be reported as [BackupObjectStoreException].
     */
    suspend fun list(
        prefix: BackupObjectKeyPrefix,
        cursor: BackupListCursor? = null,
    ): BackupObjectListPage

    /**
     * Removes the object at [key] when it is present.
     *
     * When [capabilities] reports strong read/list-after-write guarantees, a
     * successful delete should make the object absent from subsequent [stat] and
     * [list] observations covered by those guarantees.
     *
     * Expected store failures should be reported as [BackupObjectStoreException].
     */
    suspend fun delete(
        key: BackupObjectKey,
    )

    /**
     * Runs a destructive health check against the backing store.
     *
     * The default implementation creates a probe object under `health-check/`,
     * validates write, stat, full read, optional range read, list visibility and
     * delete behavior, then best-effort cleans up the probe. The result includes
     * probe metrics and the reported [capabilities].
     *
     * Verification failures are reported as
     * [BackupObjectStoreException.VerificationFailed].
     */
    suspend fun test(): BackupObjectStoreTestResult {
        val probeKey = createBackupObjectStoreTestKey()
        val payload = BACKUP_OBJECT_STORE_TEST_PAYLOAD
        var writeCompleted = false

        try {
            val writtenInfo = write(
                key = probeKey,
                mode = BackupWriteMode.Create,
            ) { sink ->
                sink.write(payload)
            }
            writeCompleted = true
            verifyBackupObjectStoreSize(
                key = probeKey,
                label = "written object",
                actual = writtenInfo.size,
                expected = payload.size.toLong(),
            )

            val statInfo = stat(probeKey)
                ?: throw BackupObjectStoreException.VerificationFailed(
                    key = probeKey,
                    reason = "probe object is not visible to stat after write",
                )
            verifyBackupObjectStoreSize(
                key = probeKey,
                label = "statted object",
                actual = statInfo.size,
                expected = payload.size.toLong(),
            )

            val readBytes = read(probeKey).readByteArrayAndClose()
            if (!payload.contentEquals(readBytes)) {
                throw BackupObjectStoreException.VerificationFailed(
                    key = probeKey,
                    reason = "probe object read returned different bytes",
                )
            }

            val rangeRead = if (capabilities.rangeRead) {
                val rangeLength = minOf(8, payload.size)
                val expectedRangeBytes = payload.copyOfRange(0, rangeLength)
                val rangeBytes = read(
                    key = probeKey,
                    range = BackupByteRange(
                        offset = 0L,
                        length = rangeLength.toLong(),
                    ),
                ).readByteArrayAndClose()
                if (!expectedRangeBytes.contentEquals(rangeBytes)) {
                    throw BackupObjectStoreException.VerificationFailed(
                        key = probeKey,
                        reason = "probe object range read returned different bytes",
                    )
                }
                true
            } else {
                null
            }

            val listed = containsBackupObjectStoreTestProbe(probeKey)
            if (!listed && capabilities.strongListAfterWrite) {
                throw BackupObjectStoreException.VerificationFailed(
                    key = probeKey,
                    reason = "probe object is not visible to list after write",
                )
            }

            delete(probeKey)
            val deleted = if (capabilities.strongReadAfterWrite) {
                val absent = isBackupObjectStoreTestProbeAbsent(probeKey)
                if (!absent) {
                    throw BackupObjectStoreException.VerificationFailed(
                        key = probeKey,
                        reason = "probe object is still visible after delete",
                    )
                }
                true
            } else {
                true
            }

            return BackupObjectStoreTestResult(
                probeKey = probeKey,
                bytesWritten = payload.size.toLong(),
                bytesRead = readBytes.size.toLong(),
                listed = listed,
                deleted = deleted,
                rangeRead = rangeRead,
                capabilities = capabilities,
            )
        } finally {
            if (writeCompleted) {
                try {
                    delete(probeKey)
                } catch (_: Exception) {
                    // Best-effort cleanup must not hide the primary probe failure.
                }
            }
        }
    }

    /**
     * Releases resources owned by this store.
     *
     * The default implementation is a no-op. Callers that open a store should
     * still invoke [close] from `finally` blocks so implementations with network,
     * file or platform handles can clean them up.
     */
    suspend fun close() {
        // no-op by default
    }
}

interface BackupObjectStoreFactory {
    suspend fun open(
        store: BackupStoreConfig,
    ): BackupObjectStore

    suspend fun test(
        store: BackupStoreConfig,
    ): BackupObjectStoreTestResult {
        val objectStore = open(store)
        try {
            return objectStore.test()
        } finally {
            objectStore.close()
        }
    }
}

private const val BACKUP_OBJECT_STORE_TEST_PREFIX_VALUE = "health-check/"

private val BACKUP_OBJECT_STORE_TEST_PREFIX = BackupObjectKeyPrefix(
    BACKUP_OBJECT_STORE_TEST_PREFIX_VALUE,
)

private val BACKUP_OBJECT_STORE_TEST_PAYLOAD =
    "keyguard-object-store-test\n".encodeToByteArray()

private fun createBackupObjectStoreTestKey(): BackupObjectKey {
    val timestamp = Clock.System.now().toEpochMilliseconds()
    val nonce = Random.nextLong().toString().replace("-", "n")
    return BackupObjectKey("$BACKUP_OBJECT_STORE_TEST_PREFIX_VALUE$timestamp-$nonce.probe")
}

private fun verifyBackupObjectStoreSize(
    key: BackupObjectKey,
    label: String,
    actual: Long?,
    expected: Long,
) {
    if (actual != null && actual != expected) {
        throw BackupObjectStoreException.VerificationFailed(
            key = key,
            reason = "$label has size $actual, expected $expected",
        )
    }
}

private suspend fun BackupObjectStore.containsBackupObjectStoreTestProbe(
    key: BackupObjectKey,
): Boolean {
    var cursor: BackupListCursor? = null
    do {
        val page = list(
            prefix = BACKUP_OBJECT_STORE_TEST_PREFIX,
            cursor = cursor,
        )
        if (page.items.any { it.key == key }) {
            return true
        }
        cursor = page.nextCursor
    } while (cursor != null)
    return false
}

private suspend fun BackupObjectStore.isBackupObjectStoreTestProbeAbsent(
    key: BackupObjectKey,
): Boolean = try {
    stat(key) == null
} catch (_: BackupObjectStoreException.NotFound) {
    true
}
