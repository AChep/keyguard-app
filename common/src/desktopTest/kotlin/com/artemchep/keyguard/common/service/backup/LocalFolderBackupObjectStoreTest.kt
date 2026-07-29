package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.util.io.FileSystemFailure
import com.artemchep.keyguard.util.io.FileSystemFailureKind
import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import com.artemchep.keyguard.util.io.atomic.AchievedSyncLevel
import com.artemchep.keyguard.util.io.atomic.AtomicFileWriteException
import com.artemchep.keyguard.util.io.atomic.AtomicPublicationOperation
import com.artemchep.keyguard.util.io.atomic.AtomicPublicationState
import com.artemchep.keyguard.util.io.atomic.AtomicPublicationUnknownException
import com.artemchep.keyguard.util.io.atomic.AtomicPublicationUnsupportedException
import com.artemchep.keyguard.util.io.atomic.AtomicSynchronizationException
import com.artemchep.keyguard.util.io.atomic.openAtomicDirectory
import com.artemchep.keyguard.util.io.toJavaFile
import com.artemchep.keyguard.util.io.toLocalPath
import kotlinx.coroutines.test.runTest
import kotlinx.io.asInputStream
import kotlinx.io.write
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.ReadOnlyFileSystemException
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Suppress("FunctionNaming")
class LocalFolderBackupObjectStoreTest {
    @Test
    fun `writes reads lists and deletes objects`() = runTest {
        val root = createTempDirectory("backup-object-store").toLocalPath()
        val store = LocalFolderBackupObjectStore(root)
        val key = BackupObjectKey("snapshots/snapshot-1.zip")
        val data = "payload".encodeToByteArray()

        val info = store.write(key) { sink ->
            sink.write(data)
        }

        assertEquals(key, info.key)
        assertEquals(data.size.toLong(), info.size)
        assertNotNull(info.atomicWriteReceipt)
        assertContentEquals(data, store.readAll(key))
        assertContentEquals(
            "ayl".encodeToByteArray(),
            store.readAll(
                key = key,
                range = BackupByteRange(
                    offset = 1L,
                    length = 3L,
                ),
            ),
        )
        assertEquals(
            listOf("snapshots/snapshot-1.zip"),
            store
                .list(BackupObjectKeyPrefix("snapshots/"))
                .items
                .map { it.key.value },
        )

        assertFailsWith<BackupObjectStoreException.AlreadyExists> {
            store.write(
                key = key,
                mode = BackupWriteMode.Create,
            ) { sink ->
                sink.write("replacement".encodeToByteArray())
            }
        }
        assertContentEquals(data, store.readAll(key))

        store.delete(key)

        assertNull(store.stat(key))
        assertEquals(
            emptyList(),
            store.list(BackupObjectKeyPrefix("snapshots/")).items,
        )
    }

    @Test
    fun `read reports typed missing and invalid range errors`() = runTest {
        val root = createTempDirectory("backup-object-store-errors").toLocalPath()
        val store = LocalFolderBackupObjectStore(root)
        val key = BackupObjectKey("objects/payload.bin")

        assertFailsWith<BackupObjectStoreException.NotFound> {
            store.readAll(key)
        }
        val directoryKey = BackupObjectKey("objects/directory.bin")
        assertTrue(root.toJavaFile().resolve(directoryKey.value).mkdirs())
        assertFailsWith<BackupObjectStoreException.NotFound> {
            store.readAll(directoryKey)
        }

        store.write(key) { sink ->
            sink.write("payload".encodeToByteArray())
        }

        val error = assertFailsWith<BackupObjectStoreException.InvalidRange> {
            store.readAll(
                key = key,
                range = BackupByteRange(
                    offset = 100L,
                    length = 1L,
                ),
            )
        }
        assertEquals(key, error.key)
        assertEquals(false, error.retryable)
    }

    @Test
    fun `write refuses to replace directory at object key`() = runTest {
        val root = createTempDirectory("backup-object-store-directory-collision").toLocalPath()
        val store = LocalFolderBackupObjectStore(root)
        val key = BackupObjectKey("objects/payload.bin")
        val directory = root.toJavaFile().resolve(key.value)
        assertTrue(directory.mkdirs())

        val createError = assertFailsWith<BackupObjectStoreException.AlreadyExists> {
            store.write(
                key = key,
                mode = BackupWriteMode.Create,
            ) { sink ->
                sink.write("payload".encodeToByteArray())
            }
        }
        assertEquals(key, createError.key)

        val replaceError = assertFailsWith<AtomicFileWriteException> {
            store.write(
                key = key,
                mode = BackupWriteMode.CreateOrReplace,
            ) { sink ->
                sink.write("payload".encodeToByteArray())
            }
        }
        assertEquals(FileSystemFailureKind.InvalidInput, replaceError.failure.kind)
        assertTrue(directory.isDirectory)
    }

    @Test
    fun `native write rejects a symbolic link below the selected backup root`() = runTest {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return@runTest
        val parent = createTempDirectory("backup-object-store-linked-parent")
        try {
            val root = Files.createDirectory(parent.resolve("root"))
            val outside = Files.createDirectory(parent.resolve("outside"))
            Files.createSymbolicLink(root.resolve("linked"), outside)
            val store = LocalFolderBackupObjectStore(root.toLocalPath())
            val key = BackupObjectKey("linked/payload.bin")

            val error = assertFailsWith<AtomicFileWriteException> {
                store.write(key) { sink ->
                    sink.write("payload".encodeToByteArray())
                }
            }

            assertEquals(FileSystemFailureKind.InvalidInput, error.failure.kind)
            assertFalse(Files.exists(outside.resolve("payload.bin")))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @OptIn(InternalKeyguardIoApi::class)
    @Test
    fun `native write remains successful when selected root is renamed after open`() = runTest {
        val parent = createTempDirectory("backup-object-store-root-rename")
        try {
            val root = Files.createDirectory(parent.resolve("root"))
            val renamed = parent.resolve("renamed")
            val store = LocalFolderBackupObjectStore(
                root = root.toLocalPath(),
                openInput = { path -> Files.newInputStream(path) },
                atomicDirectoryOpen = { selectedRoot ->
                    openAtomicDirectory(selectedRoot).also {
                        Files.move(root, renamed)
                    }
                },
            )
            val key = BackupObjectKey("nested/payload.bin")
            val payload = "retained root".encodeToByteArray()

            val info = store.write(key) { sink ->
                sink.write(payload)
            }

            assertEquals(payload.size.toLong(), info.size)
            assertNull(info.updatedAt)
            assertNotNull(info.atomicWriteReceipt)
            assertContentEquals(payload, Files.readAllBytes(renamed.resolve(key.value)))
            assertFalse(Files.exists(root))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `read reports permission denied when existing regular file cannot be opened`() = runTest {
        val root = createTempDirectory("backup-object-store-read-denied").toLocalPath()
        val key = BackupObjectKey("objects/payload.bin")
        val file = root.toJavaFile().resolve(key.value)
        assertTrue(requireNotNull(file.parentFile).mkdirs())
        file.writeText("payload")
        val store = LocalFolderBackupObjectStore(root) {
            throw FileNotFoundException("permission denied")
        }

        val error = assertFailsWith<BackupObjectStoreException.PermissionDenied> {
            store.readAll(key)
        }

        assertEquals(BackupObjectStoreOperation.Read, error.operation)
        assertEquals(key, error.key)
        assertTrue(error.cause is FileNotFoundException)
    }

    @Test
    fun `streaming read failure is transient`() = runTest {
        val root = createTempDirectory("backup-object-store-stream-read-error").toLocalPath()
        val key = BackupObjectKey("objects/payload.bin")
        val file = root.toJavaFile().resolve(key.value)
        assertTrue(requireNotNull(file.parentFile).mkdirs())
        file.writeText("payload")
        val cause = IOException("stream read failed")
        val store = LocalFolderBackupObjectStore(root) {
            FailingReadInputStream(cause)
        }

        val error = assertFailsWith<BackupObjectStoreException.Transient> {
            store.readAll(key)
        }

        assertEquals(BackupObjectStoreOperation.Read, error.operation)
        assertEquals(key, error.key)
        assertEquals(cause, error.cause)
    }

    @Test
    fun `streaming close failure is transient`() = runTest {
        val root = createTempDirectory("backup-object-store-stream-close-error").toLocalPath()
        val key = BackupObjectKey("objects/payload.bin")
        val file = root.toJavaFile().resolve(key.value)
        assertTrue(requireNotNull(file.parentFile).mkdirs())
        file.writeText("payload")
        val cause = IOException("stream close failed")
        val store = LocalFolderBackupObjectStore(root) {
            FailingCloseInputStream(
                bytes = "payload".encodeToByteArray(),
                cause = cause,
            )
        }

        val source = store.read(key)
        assertContentEquals("payload".encodeToByteArray(), source.asInputStream().readBytes())
        val error = assertFailsWith<BackupObjectStoreException.Transient> {
            source.close()
        }

        assertEquals(BackupObjectStoreOperation.Read, error.operation)
        assertEquals(key, error.key)
        assertEquals(cause, error.cause)
    }

    @Test
    fun `create race maps existing target to already exists`() = runTest {
        val root = createTempDirectory("backup-object-store-create-race").toLocalPath()
        val key = BackupObjectKey("objects/payload.bin")
        val store = LocalFolderBackupObjectStore(root) { _, target ->
            throw FileAlreadyExistsException(target.toString())
        }

        val error = assertFailsWith<BackupObjectStoreException.AlreadyExists> {
            store.write(
                key = key,
                mode = BackupWriteMode.Create,
            ) { sink ->
                sink.write("payload".encodeToByteArray())
            }
        }

        assertEquals(key, error.key)
        assertTrue(error.cause is FileAlreadyExistsException)
        assertFalse(root.toJavaFile().resolve(key.value).exists())
    }

    @Test
    fun `replace failure from existing target is transient and preserves existing object`() = runTest {
        val root = createTempDirectory("backup-object-store-replace-existing-failure").toLocalPath()
        val key = BackupObjectKey("objects/payload.bin")
        val existing = "existing".encodeToByteArray()
        val normalStore = LocalFolderBackupObjectStore(root)
        normalStore.write(key) { sink ->
            sink.write(existing)
        }
        val failingStore = LocalFolderBackupObjectStore(root) { _, target ->
            throw FileAlreadyExistsException(target.toString())
        }

        val error = assertFailsWith<BackupObjectStoreException.Transient> {
            failingStore.write(
                key = key,
                mode = BackupWriteMode.CreateOrReplace,
            ) { sink ->
                sink.write("replacement".encodeToByteArray())
            }
        }

        assertEquals(BackupObjectStoreOperation.Write, error.operation)
        assertEquals(key, error.key)
        assertTrue(error.cause is FileAlreadyExistsException)
        assertContentEquals(existing, normalStore.readAll(key))
    }

    @Test
    fun `write reports typed transient error for local io failure`() = runTest {
        val root = createTempDirectory("backup-object-store-write-error").toLocalPath()
        val store = LocalFolderBackupObjectStore(root)
        val key = BackupObjectKey("objects/payload.bin")

        val error = assertFailsWith<BackupObjectStoreException.Transient> {
            store.write(key) {
                throw IOException("disk full")
            }
        }

        assertEquals(BackupObjectStoreOperation.Write, error.operation)
        assertEquals(key, error.key)
        assertTrue(error.cause is IOException)
        assertFalse(root.toJavaFile().resolve(key.value).exists())
    }
}

@Suppress("FunctionNaming")
class LocalFolderBackupObjectStoreWriteFailureTest {
    @Test
    fun `native write permission failure is nonretryable`() = runTest {
        val root = createTempDirectory("backup-object-store-write-permission").toLocalPath()
        val key = BackupObjectKey("objects/payload.bin")
        val store = LocalFolderBackupObjectStore(root) { _, _ ->
            throw atomicWriteFailure(FileSystemFailureKind.PermissionDenied)
        }

        val error = assertFailsWith<BackupObjectStoreException.PermissionDenied> {
            store.write(key) { sink ->
                sink.write("payload".encodeToByteArray())
            }
        }

        assertFalse(error.retryable)
        assertEquals(key, error.key)
        assertTrue(error.cause is AtomicFileWriteException)
        assertFalse(root.toJavaFile().resolve(key.value).exists())
    }

    @Test
    fun `native readonly filesystem failure is nonretryable`() = runTest {
        val root = createTempDirectory("backup-object-store-write-readonly").toLocalPath()
        val key = BackupObjectKey("objects/payload.bin")
        val store = LocalFolderBackupObjectStore(root) { _, _ ->
            throw atomicWriteFailure(FileSystemFailureKind.ReadOnlyFilesystem)
        }

        val error = assertFailsWith<BackupObjectStoreException.PermissionDenied> {
            store.write(key) { sink ->
                sink.write("payload".encodeToByteArray())
            }
        }

        assertFalse(error.retryable)
        assertEquals(key, error.key)
        assertTrue(error.cause is AtomicFileWriteException)
    }

    @Test
    fun `jvm readonly filesystem failure is nonretryable`() = runTest {
        val root = createTempDirectory("backup-object-store-write-jvm-readonly").toLocalPath()
        val key = BackupObjectKey("objects/payload.bin")
        val store = LocalFolderBackupObjectStore(root) { _, _ ->
            throw ReadOnlyFileSystemException()
        }

        val error = assertFailsWith<BackupObjectStoreException.PermissionDenied> {
            store.write(key) { sink ->
                sink.write("payload".encodeToByteArray())
            }
        }

        assertFalse(error.retryable)
        assertEquals(key, error.key)
        assertTrue(error.cause is ReadOnlyFileSystemException)
    }

    @Test
    fun `native unsupported publication is nonretryable`() = runTest {
        val root = createTempDirectory("backup-object-store-write-unsupported").toLocalPath()
        val key = BackupObjectKey("objects/payload.bin")
        val store = LocalFolderBackupObjectStore(root) { _, _ ->
            throw atomicWriteFailure(FileSystemFailureKind.Unsupported)
        }

        val error = assertFailsWith<BackupObjectStoreException.AtomicWriteUnsupported> {
            store.write(key) { sink ->
                sink.write("payload".encodeToByteArray())
            }
        }

        assertFalse(error.retryable)
        assertEquals(key, error.key)
        assertTrue(error.cause is AtomicFileWriteException)
    }

    @Test
    fun `dedicated unsupported publication is nonretryable`() = runTest {
        val root = createTempDirectory("backup-object-store-write-dedicated-unsupported").toLocalPath()
        val key = BackupObjectKey("objects/payload.bin")
        val store = LocalFolderBackupObjectStore(root) { _, _ ->
            throw AtomicPublicationUnsupportedException("Atomic publication is unsupported")
        }

        val error = assertFailsWith<BackupObjectStoreException.AtomicWriteUnsupported> {
            store.write(key) { sink ->
                sink.write("payload".encodeToByteArray())
            }
        }

        assertFalse(error.retryable)
        assertEquals(key, error.key)
        assertTrue(error.cause is AtomicPublicationUnsupportedException)
    }

    @Test
    fun `unknown native publication is never classified as transient`() = runTest {
        val root = createTempDirectory("backup-object-store-write-publication-unknown").toLocalPath()
        val key = BackupObjectKey("objects/payload.bin")
        val expected = AtomicPublicationUnknownException(
            message = "Atomic publication acknowledgement was lost",
            publicationOperation = AtomicPublicationOperation.Rename,
            cleanupIncomplete = true,
            failure = FileSystemFailure(
                kind = FileSystemFailureKind.Other,
            ),
        )
        val store = LocalFolderBackupObjectStore(root) { _, _ ->
            throw expected
        }

        val error = assertFailsWith<BackupObjectStoreException.PublicationUnknown> {
            store.write(key) { sink ->
                sink.write("payload".encodeToByteArray())
            }
        }

        assertFalse(error.retryable)
        assertEquals(key, error.key)
        assertSame(expected, error.cause)
    }

    @Test
    fun `published synchronization unknown is not classified as transient`() = runTest {
        val root = createTempDirectory("backup-object-store-write-synchronization-unknown")
            .toLocalPath()
        val key = BackupObjectKey("objects/payload.bin")
        val expected = AtomicSynchronizationException(
            message = "Backup object was published without confirmed synchronization",
            achievedSyncLevel = AchievedSyncLevel.FileSynchronized,
            failure = FileSystemFailure(
                kind = FileSystemFailureKind.DurabilityUnavailable,
            ),
        )
        val store = LocalFolderBackupObjectStore(root) { source, target ->
            Files.move(source, target, REPLACE_EXISTING)
            throw expected
        }
        val replacement = "replacement".encodeToByteArray()

        val error =
            assertFailsWith<BackupObjectStoreException.PublishedSynchronizationUnknown> {
                store.write(
                    key = key,
                    mode = BackupWriteMode.CreateOrReplace,
                ) { sink ->
                    sink.write(replacement)
                }
            }

        assertFalse(error.retryable)
        assertEquals(key, error.key)
        assertEquals(AchievedSyncLevel.FileSynchronized, error.achievedSyncLevel)
        assertFalse(error.cleanupIncomplete)
        assertSame(expected, error.cause)
        assertContentEquals(replacement, root.toJavaFile().resolve(key.value).readBytes())
    }

    @Test
    fun `published synchronization unknown remains primary over permission and cleanup failures`() =
        runTest {
            val root = createTempDirectory("backup-object-store-write-sync-and-cleanup-unknown")
                .toLocalPath()
            val key = BackupObjectKey("objects/payload.bin")
            val expected = AtomicSynchronizationException(
                message = "Backup object synchronization and cleanup were not confirmed",
                achievedSyncLevel = AchievedSyncLevel.FileSynchronized,
                cleanupIncomplete = true,
                failure = FileSystemFailure(
                    kind = FileSystemFailureKind.PermissionDenied,
                ),
            )
            val store = LocalFolderBackupObjectStore(root) { source, target ->
                Files.move(source, target, REPLACE_EXISTING)
                throw expected
            }
            val replacement = "replacement".encodeToByteArray()

            val error =
                assertFailsWith<BackupObjectStoreException.PublishedSynchronizationUnknown> {
                    store.write(
                        key = key,
                        mode = BackupWriteMode.CreateOrReplace,
                    ) { sink ->
                        sink.write(replacement)
                    }
                }

            assertFalse(error.retryable)
            assertEquals(key, error.key)
            assertEquals(AchievedSyncLevel.FileSynchronized, error.achievedSyncLevel)
            assertTrue(error.cleanupIncomplete)
            assertSame(expected, error.cause)
            assertContentEquals(replacement, root.toJavaFile().resolve(key.value).readBytes())
        }

    @Test
    fun `other native write failure remains transient`() = runTest {
        val root = createTempDirectory("backup-object-store-write-native-other").toLocalPath()
        val key = BackupObjectKey("objects/payload.bin")
        val store = LocalFolderBackupObjectStore(root) { _, _ ->
            throw atomicWriteFailure(FileSystemFailureKind.Other)
        }

        val error = assertFailsWith<BackupObjectStoreException.Transient> {
            store.write(key) { sink ->
                sink.write("payload".encodeToByteArray())
            }
        }

        assertTrue(error.retryable)
        assertEquals(key, error.key)
        assertTrue(error.cause is AtomicFileWriteException)
    }

    @Test
    fun `internal native write failure is not converted into a retryable store error`() = runTest {
        val root = createTempDirectory("backup-object-store-write-native-internal").toLocalPath()
        val key = BackupObjectKey("objects/payload.bin")
        val expected = atomicWriteFailure(FileSystemFailureKind.Internal)
        val store = LocalFolderBackupObjectStore(root) { _, _ ->
            throw expected
        }

        val error = assertFailsWith<AtomicFileWriteException> {
            store.write(key) { sink ->
                sink.write("payload".encodeToByteArray())
            }
        }

        assertSame(expected, error)
        assertFalse(root.toJavaFile().resolve(key.value).exists())
    }

    @Test
    fun `invalid native write failure is not converted into a retryable store error`() = runTest {
        val root = createTempDirectory("backup-object-store-write-native-invalid").toLocalPath()
        val key = BackupObjectKey("objects/payload.bin")
        val expected = atomicWriteFailure(FileSystemFailureKind.InvalidInput)
        val store = LocalFolderBackupObjectStore(root) { _, _ ->
            throw expected
        }

        val error = assertFailsWith<AtomicFileWriteException> {
            store.write(key) { sink ->
                sink.write("payload".encodeToByteArray())
            }
        }

        assertSame(expected, error)
        assertFalse(root.toJavaFile().resolve(key.value).exists())
    }
}

@Suppress("FunctionNaming")
class LocalFolderBackupObjectStoreOperationsTest {
    // A publication that succeeds but leaves a temporary behind is reported
    // through the commit receipt, not an exception, so the store no longer
    // needs a reconcile path: the write returns normally and the orphan
    // sweeper reclaims the leftover artifact.

    @Test
    fun `create or replace replaces existing object`() = runTest {
        val root = createTempDirectory("backup-object-store-replace").toLocalPath()
        val store = LocalFolderBackupObjectStore(root)
        val key = BackupObjectKey("snapshots/snapshot-1.zip")
        val replacement = "replacement".encodeToByteArray()
        store.write(key) { sink ->
            sink.write("original".encodeToByteArray())
        }

        val info = store.write(
            key = key,
            mode = BackupWriteMode.CreateOrReplace,
        ) { sink ->
            sink.write(replacement)
        }

        assertEquals(key, info.key)
        assertEquals(replacement.size.toLong(), info.size)
        assertNotNull(info.atomicWriteReceipt)
        assertContentEquals(replacement, store.readAll(key))
        assertEquals(replacement.size.toLong(), store.stat(key)?.size)
        assertEquals(
            listOf(key.value),
            store
                .list(BackupObjectKeyPrefix("snapshots/"))
                .items
                .map { it.key.value },
        )
    }

    @Test
    fun `delete reports typed error when filesystem refuses removal`() = runTest {
        val root = createTempDirectory("backup-object-store-delete-error").toLocalPath()
        val store = LocalFolderBackupObjectStore(root)
        val key = BackupObjectKey("objects/payload.bin")
        val obstructingDirectory = root
            .toJavaFile()
            .resolve(key.value)
        assertTrue(obstructingDirectory.mkdirs())
        obstructingDirectory
            .resolve("child.txt")
            .writeText("child")

        val error = assertFailsWith<BackupObjectStoreException.Transient> {
            store.delete(key)
        }

        assertEquals(BackupObjectStoreOperation.Delete, error.operation)
        assertEquals(key, error.key)
        assertTrue(obstructingDirectory.exists())
    }

    @Test
    fun `stat and list only report files`() = runTest {
        val root = createTempDirectory("backup-object-store-regular-files")
        val rootFile = root.toFile()
        val directory = rootFile.resolve("snapshots")
        assertTrue(directory.mkdirs())
        assertTrue(directory.resolve("nested").mkdirs())
        val store = LocalFolderBackupObjectStore(root.toLocalPath())
        val key = BackupObjectKey("snapshots/snapshot-1.zip")

        store.write(key) { sink ->
            sink.write("payload".encodeToByteArray())
        }

        assertNull(store.stat(BackupObjectKey("snapshots")))
        assertEquals(
            listOf(key.value),
            store
                .list(BackupObjectKeyPrefix(""))
                .items
                .map { it.key.value },
        )
    }

    @Test
    fun `test validates backend and cleans up probe`() = runTest {
        val root = createTempDirectory("backup-object-store-test").toLocalPath()
        val store = LocalFolderBackupObjectStore(root)

        val result = store.test()

        assertTrue(result.probeKey.value.startsWith("health-check/"))
        assertEquals(result.bytesWritten, result.bytesRead)
        assertEquals(true, result.listed)
        assertEquals(true, result.deleted)
        assertEquals(true, result.rangeRead)
        assertEquals(store.capabilities, result.capabilities)
        assertTrue(store.capabilities.atomicReplace)
        assertFalse(root.toJavaFile().resolve("health-check").exists())
    }

    @Test
    fun `unsupported atomic move is nonretryable and does not fall back`() = runTest {
        val root = createTempDirectory("backup-object-store-atomic-move").toLocalPath()
        val key = BackupObjectKey("objects/payload.bin")
        var moveAttempts = 0
        val store = LocalFolderBackupObjectStore(root) { source, target ->
            moveAttempts += 1
            throw AtomicMoveNotSupportedException(
                source.toString(),
                target.toString(),
                "test filesystem",
            )
        }

        val error = assertFailsWith<BackupObjectStoreException.AtomicWriteUnsupported> {
            store.write(key) { sink ->
                sink.write("payload".encodeToByteArray())
            }
        }

        assertEquals(1, moveAttempts)
        assertFalse(error.retryable)
        assertEquals(key, error.key)
        assertTrue(error.cause is AtomicMoveNotSupportedException)
        assertFalse(root.toJavaFile().resolve(key.value).exists())
    }

    @Test
    fun `failed atomic replacement leaves existing object unchanged`() = runTest {
        val root = createTempDirectory("backup-object-store-atomic-replace").toLocalPath()
        val key = BackupObjectKey("objects/payload.bin")
        val existing = "existing".encodeToByteArray()
        val normalStore = LocalFolderBackupObjectStore(root)
        normalStore.write(key) { sink ->
            sink.write(existing)
        }
        val failingStore = LocalFolderBackupObjectStore(root) { source, target ->
            throw AtomicMoveNotSupportedException(
                source.toString(),
                target.toString(),
                "test filesystem",
            )
        }

        assertFailsWith<BackupObjectStoreException.AtomicWriteUnsupported> {
            failingStore.write(
                key = key,
                mode = BackupWriteMode.CreateOrReplace,
            ) { sink ->
                sink.write("replacement".encodeToByteArray())
            }
        }

        assertContentEquals(existing, normalStore.readAll(key))
    }

    @Test
    fun `factory test validates configured local folder`() = runTest {
        val root = createTempDirectory("backup-object-store-factory")
        val factory = LocalFolderBackupObjectStoreFactory()

        val result = factory.test(
            BackupStoreConfig.Local(
                path = root.toString(),
            ),
        )

        assertTrue(result.probeKey.value.startsWith("health-check/"))
        assertEquals(result.bytesWritten, result.bytesRead)
        assertFalse(root.resolve("health-check").toFile().exists())
    }

    @Test
    fun `factory test accepts configured file uri`() = runTest {
        val root = createTempDirectory("backup-object-store-factory-uri")
            .resolve("backup folder")
        val factory = LocalFolderBackupObjectStoreFactory()

        val result = factory.test(
            BackupStoreConfig.Local(
                path = root.toUri().toString(),
            ),
        )

        assertTrue(result.probeKey.value.startsWith("health-check/"))
        assertEquals(result.bytesWritten, result.bytesRead)
        assertFalse(root.resolve("health-check").toFile().exists())
    }
}

private suspend fun LocalFolderBackupObjectStore.readAll(
    key: BackupObjectKey,
    range: BackupByteRange? = null,
): ByteArray = read(
    key = key,
    range = range,
).use { source ->
    source.asInputStream().readBytes()
}

private class FailingReadInputStream(
    private val cause: IOException,
) : InputStream() {
    override fun read(): Int {
        throw cause
    }
}

private class FailingCloseInputStream(
    bytes: ByteArray,
    private val cause: IOException,
) : ByteArrayInputStream(bytes) {
    override fun close() {
        throw cause
    }
}

private fun atomicWriteFailure(
    kind: FileSystemFailureKind,
    publicationState: AtomicPublicationState = AtomicPublicationState.NotPublished,
): AtomicFileWriteException = AtomicFileWriteException(
    message = "Native atomic write failed",
    publicationState = publicationState,
    failure = FileSystemFailure(
        kind = kind,
        diagnostic = null,
    ),
)
