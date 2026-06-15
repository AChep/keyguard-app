package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.platform.toJavaFile
import com.artemchep.keyguard.platform.toLocalPath
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.asInputStream
import kotlinx.io.write

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

        listOf(
            BackupWriteMode.Create,
            BackupWriteMode.CreateOrReplace,
        ).forEach { mode ->
            val error = assertFailsWith<BackupObjectStoreException.AlreadyExists> {
                store.write(
                    key = key,
                    mode = mode,
                ) { sink ->
                    sink.write("payload".encodeToByteArray())
                }
            }

            assertEquals(key, error.key)
            assertTrue(directory.isDirectory)
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
        assertFalse(store.capabilities.atomicReplace)
        assertFalse(root.toJavaFile().resolve("health-check").exists())
    }

    @Test
    fun `write does not fall back when atomic move is unsupported`() = runTest {
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

        val error = assertFailsWith<BackupObjectStoreException.Transient> {
            store.write(key) { sink ->
                sink.write("payload".encodeToByteArray())
            }
        }

        assertEquals(1, moveAttempts)
        assertEquals(BackupObjectStoreOperation.Write, error.operation)
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

        assertFailsWith<BackupObjectStoreException.Transient> {
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
}
