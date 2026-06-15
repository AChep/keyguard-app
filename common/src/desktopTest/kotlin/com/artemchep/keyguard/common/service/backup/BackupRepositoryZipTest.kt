package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.model.Password
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.lingala.zip4j.ZipFile
import com.artemchep.keyguard.platform.toLocalPath
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupRepositoryZipTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val repository = BackupRepositoryZipImpl(
        json = json,
    )

    @Test
    fun `writes encrypted repository index snapshot and blob zips`() = runTest {
        val root = createTempDirectory("keyguard-backup-repository").toLocalPath()
        val store = LocalFolderBackupObjectStore(root)
        val password = Password("backup-password")
        val now = Instant.fromEpochMilliseconds(1L)

        val metadata = repository.getOrCreateMetadata(
            store = store,
            password = password,
            nowProvider = { now },
            repoIdProvider = { "repo-1" },
        )
        assertEquals("repo-1", metadata.repoId)
        assertEquals(
            metadata,
            repository.getOrCreateMetadata(
                store = store,
                password = password,
                nowProvider = {
                    error("Existing repository metadata should not request a new timestamp.")
                },
                repoIdProvider = {
                    error("Existing repository metadata should not request a new repo id.")
                },
            ),
        )

        val index = index(indexId = "index-1", generation = 1L, updatedAt = now)
        repository.writeIndex(store, password, index)
        assertEquals(listOf(index), repository.readIndexes(store, password))
        assertEquals(
            listOf("00000000000000000001-"),
            File(root.value)
                .resolve("indexes")
                .listFiles()
                .orEmpty()
                .map { file -> file.name.take("00000000000000000001-".length) },
        )

        val blobPath = "blobs/ab/cd/abcdef.zip"
        val blobPassword = Password("blob-password")
        val data = "attachment".encodeToByteArray()
        repository.writeBlob(
            store = store,
            objectPassword = blobPassword,
            blobPath = blobPath,
        ) { sink ->
            sink.write(data)
        }
        assertTrue(repository.hasBlob(store, blobPath))

        val manifest = BackupSnapshotManifest(
            snapshotId = "snapshot-1",
            createdAt = now,
            options = BackupSnapshotOptions(
                includeAttachments = true,
            ),
            vault = BackupSnapshotVault(
                size = 2L,
            ),
            attachments = emptyList(),
            stats = BackupSnapshotStats(
                cipherCount = 0,
                attachmentCount = 0,
                newBlobCount = 0,
                reusedBlobCount = 0,
            ),
        )
        repository.writeSnapshot(
            store = store,
            objectPassword = Password("snapshot-password"),
            snapshotId = "snapshot-1",
            manifest = manifest,
            vaultJson = "{}",
        )

        assertEquals(listOf("snapshot-1"), repository.listSnapshotIds(store))
        assertEquals(
            manifest,
            repository.readSnapshotManifest(store, Password("snapshot-password"), "snapshot-1"),
        )

        val blobFile = java.io.File(root.value).resolve(blobPath)
        val header = ZipFile(blobFile, blobPassword.value.toCharArray())
            .getFileHeader("attachment.bin")
            ?: error("Missing attachment blob entry")
        assertTrue(header.isEncrypted)
        assertFailsWith<Exception> {
            val plainHeader = ZipFile(blobFile)
                .getFileHeader("attachment.bin")
                ?: error("Missing attachment blob entry")
            ZipFile(blobFile)
                .getInputStream(plainHeader)
                .use { inputStream -> inputStream.readBytes() }
        }
        assertFailsWith<Exception> {
            val repoPasswordHeader = ZipFile(blobFile, password.value.toCharArray())
                .getFileHeader("attachment.bin")
                ?: error("Missing attachment blob entry")
            ZipFile(blobFile, password.value.toCharArray())
                .getInputStream(repoPasswordHeader)
                .use { inputStream -> inputStream.readBytes() }
        }
        val restored = ZipFile(blobFile, blobPassword.value.toCharArray())
            .getInputStream(header)
            .use { inputStream -> inputStream.readBytes() }
        assertContentEquals(data, restored)
    }

    @Test
    fun `writes unencrypted repository index snapshot and blob zips without password`() = runTest {
        val root = createTempDirectory("keyguard-backup-repository").toLocalPath()
        val store = LocalFolderBackupObjectStore(root)
        val password: Password? = null
        val now = Instant.fromEpochMilliseconds(1L)

        val metadata = repository.getOrCreateMetadata(
            store = store,
            password = password,
            nowProvider = { now },
            repoIdProvider = { "repo-1" },
        )
        assertEquals("repo-1", metadata.repoId)
        assertEquals("zip-none", metadata.crypto.archive)
        assertFalse(metadata.features.contains("encrypted-zip-metadata"))

        val index = index(indexId = "index-1", generation = 1L, updatedAt = now)
        repository.writeIndex(store, password, index)
        assertEquals(listOf(index), repository.readIndexes(store, password))

        val blobPath = "blobs/ab/cd/abcdef.zip"
        val data = "attachment".encodeToByteArray()
        repository.writeBlob(
            store = store,
            objectPassword = password,
            blobPath = blobPath,
        ) { sink ->
            sink.write(data)
        }

        val manifest = BackupSnapshotManifest(
            snapshotId = "snapshot-1",
            createdAt = now,
            options = BackupSnapshotOptions(
                includeAttachments = true,
            ),
            vault = BackupSnapshotVault(
                size = 2L,
            ),
            attachments = emptyList(),
            stats = BackupSnapshotStats(
                cipherCount = 0,
                attachmentCount = 0,
                newBlobCount = 0,
                reusedBlobCount = 0,
            ),
        )
        repository.writeSnapshot(
            store = store,
            objectPassword = password,
            snapshotId = "snapshot-1",
            manifest = manifest,
            vaultJson = "{}",
        )

        assertEquals(listOf("snapshot-1"), repository.listSnapshotIds(store))
        assertEquals(manifest, repository.readSnapshotManifest(store, password, "snapshot-1"))

        val blobFile = java.io.File(root.value).resolve(blobPath)
        val header = ZipFile(blobFile)
            .getFileHeader("attachment.bin")
            ?: error("Missing attachment blob entry")
        assertFalse(header.isEncrypted)
        val restored = ZipFile(blobFile)
            .getInputStream(header)
            .use { inputStream -> inputStream.readBytes() }
        assertContentEquals(data, restored)
    }

    @Test
    fun `rejects enabling password on existing unencrypted repository`() = runTest {
        val root = createTempDirectory("keyguard-backup-repository-password-mode").toLocalPath()
        val store = LocalFolderBackupObjectStore(root)
        val now = Instant.fromEpochMilliseconds(1L)

        repository.getOrCreateMetadata(
            store = store,
            password = null,
            nowProvider = { now },
            repoIdProvider = { "repo-1" },
        )

        val error = assertFailsWith<IllegalStateException> {
            repository.getOrCreateMetadata(
                store = store,
                password = Password("backup-password"),
                nowProvider = { now },
                repoIdProvider = { "repo-2" },
            )
        }
        assertEquals(
            "Backup repository password mode cannot be changed after creation.",
            error.message,
        )
    }

    @Test
    fun `rejects existing repository without generational index features`() = runTest {
        val root = createTempDirectory("keyguard-backup-repository-legacy-format").toLocalPath()
        val store = LocalFolderBackupObjectStore(root)
        val now = Instant.fromEpochMilliseconds(1L)
        val legacy = BackupRepositoryMetadata(
            repoId = "repo-legacy",
            createdAt = now,
            features = listOf(
                "plain-zip-metadata",
                "snapshot-vault-json",
                "attachment-blob-zips",
            ),
            crypto = BackupRepositoryCrypto(
                archive = "zip-none",
                objectArchive = "zip-none",
            ),
            layout = BackupRepositoryLayout(
                repo = "repo.zip",
                indexes = "legacy-indexes/",
                snapshots = "snapshots/",
                blobs = "blobs/",
            ),
        )
        writeRepoMetadata(
            root = File(root.value),
            metadata = legacy,
        )

        val error = assertFailsWith<IllegalStateException> {
            repository.getOrCreateMetadata(
                store = store,
                password = null,
                nowProvider = { now },
                repoIdProvider = { "repo-2" },
            )
        }

        assertEquals(
            "Backup repository format is not supported by this version.",
            error.message,
        )
    }

    @Test
    fun `readIndexes returns latest readable generation only`() = runTest {
        val root = createTempDirectory("keyguard-backup-repository-indexes").toLocalPath()
        val store = LocalFolderBackupObjectStore(root)
        val rootFile = File(root.value)
        val first = index(
            indexId = "index-1",
            generation = 1L,
            updatedAt = Instant.fromEpochMilliseconds(1L),
        )
        val secondA = index(
            indexId = "index-2a",
            generation = 2L,
            updatedAt = Instant.fromEpochMilliseconds(2L),
        )
        val secondB = index(
            indexId = "index-2b",
            generation = 2L,
            updatedAt = Instant.fromEpochMilliseconds(3L),
        )

        repository.writeIndex(store, null, first)
        repository.writeIndex(store, null, secondA)
        repository.writeIndex(store, null, secondB)
        rootFile.resolve("indexes/00000000000000000003-corrupt.zip")
            .writeBytes("not-a-zip".encodeToByteArray())

        val indexFiles = assertNotNull(rootFile.resolve("indexes").listFiles())
            .map { file -> file.name }
            .sorted()
        assertEquals(4, indexFiles.size)
        assertTrue(indexFiles.any { name -> name.startsWith("00000000000000000001-") })
        assertTrue(indexFiles.any { name -> name.startsWith("00000000000000000002-") })
        assertTrue(indexFiles.any { name -> name.startsWith("00000000000000000003-") })
        assertTrue(rootFile.resolve("indexes").isDirectory)
        assertEquals(listOf(secondB, secondA), repository.readIndexes(store, null))
    }

    @Test
    fun `readIndexes follows paginated object store listing`() = runTest {
        val root = createTempDirectory("keyguard-backup-repository-paged-indexes").toLocalPath()
        val store = PaginatedBackupObjectStore(LocalFolderBackupObjectStore(root))
        val first = index(
            indexId = "index-1",
            generation = 1L,
            updatedAt = Instant.fromEpochMilliseconds(1L),
        )
        val secondA = index(
            indexId = "index-2a",
            generation = 2L,
            updatedAt = Instant.fromEpochMilliseconds(2L),
        )
        val secondB = index(
            indexId = "index-2b",
            generation = 2L,
            updatedAt = Instant.fromEpochMilliseconds(3L),
        )

        repository.writeIndex(store, null, first)
        repository.writeIndex(store, null, secondA)
        repository.writeIndex(store, null, secondB)

        assertEquals(listOf(secondB, secondA), repository.readIndexes(store, null))
    }

    @Test
    fun `listSnapshotIds follows paginated object store listing`() = runTest {
        val root = createTempDirectory("keyguard-backup-repository-paged-snapshots").toLocalPath()
        val store = PaginatedBackupObjectStore(LocalFolderBackupObjectStore(root))

        repository.writeSnapshot(
            store = store,
            objectPassword = null,
            snapshotId = "snapshot-1",
            manifest = manifest("snapshot-1"),
            vaultJson = "{}",
        )
        repository.writeSnapshot(
            store = store,
            objectPassword = null,
            snapshotId = "snapshot-2",
            manifest = manifest("snapshot-2"),
            vaultJson = "{}",
        )

        assertEquals(listOf("snapshot-1", "snapshot-2"), repository.listSnapshotIds(store))
    }

    @Test
    fun `writeBlob reuses existing readable blob instead of replacing it`() = runTest {
        val root = createTempDirectory("keyguard-backup-repository-blob-reuse").toLocalPath()
        val store = LocalFolderBackupObjectStore(root)
        val blobPath = "blobs/ab/cd/abcdef.zip"
        val data = "attachment".encodeToByteArray()

        val firstSize = repository.writeBlob(
            store = store,
            objectPassword = null,
            blobPath = blobPath,
        ) { sink ->
            sink.write(data)
        }
        val secondSize = repository.writeBlob(
            store = store,
            objectPassword = null,
            blobPath = blobPath,
        ) {
            error("Existing blobs must be reused without rewriting.")
        }

        assertEquals(firstSize, secondSize)
        val blobFile = File(root.value).resolve(blobPath)
        val header = ZipFile(blobFile)
            .getFileHeader("attachment.bin")
            ?: error("Missing attachment blob entry")
        val restored = ZipFile(blobFile)
            .getInputStream(header)
            .use { inputStream -> inputStream.readBytes() }
        assertContentEquals(data, restored)
    }

    @Test
    fun `validateBlob accepts readable encrypted and unencrypted blob zips`() = runTest {
        val root = createTempDirectory("keyguard-backup-repository-blob-validation").toLocalPath()
        val store = LocalFolderBackupObjectStore(root)
        val encryptedPassword = Password("blob-password")

        repository.writeBlob(
            store = store,
            objectPassword = null,
            blobPath = "blobs/00/00/plain.zip",
        ) { sink ->
            sink.write("plain".encodeToByteArray())
        }
        repository.writeBlob(
            store = store,
            objectPassword = encryptedPassword,
            blobPath = "blobs/00/00/encrypted.zip",
        ) { sink ->
            sink.write("encrypted".encodeToByteArray())
        }

        assertEquals(
            BackupBlobValidationResult.Valid,
            repository.validateBlob(store, null, "blobs/00/00/plain.zip"),
        )
        assertEquals(
            BackupBlobValidationResult.Valid,
            repository.validateBlob(store, encryptedPassword, "blobs/00/00/encrypted.zip"),
        )
    }

    @Test
    fun `validateBlob rejects unreadable blob zips`() = runTest {
        val root = createTempDirectory("keyguard-backup-repository-invalid-blobs").toLocalPath()
        val store = LocalFolderBackupObjectStore(root)
        val rootFile = File(root.value)
        val encryptedPath = "blobs/00/00/encrypted.zip"
        val truncatedPath = "blobs/00/00/truncated.zip"

        writeBytes(rootFile.resolve("blobs/00/00/empty.zip"), ByteArray(0))
        writeBytes(rootFile.resolve("blobs/00/00/not-a-zip.zip"), "not-a-zip".encodeToByteArray())
        writeZipEntry(
            file = rootFile.resolve("blobs/00/00/missing-entry.zip"),
            entryName = "other.bin",
            bytes = "payload".encodeToByteArray(),
        )
        repository.writeBlob(
            store = store,
            objectPassword = Password("blob-password"),
            blobPath = encryptedPath,
        ) { sink ->
            sink.write("encrypted".encodeToByteArray())
        }
        repository.writeBlob(
            store = store,
            objectPassword = null,
            blobPath = truncatedPath,
        ) { sink ->
            sink.write("truncated".encodeToByteArray())
        }
        val truncatedFile = rootFile.resolve(truncatedPath)
        truncatedFile.writeBytes(
            truncatedFile.readBytes()
                .copyOf(1),
        )

        assertEquals(
            BackupBlobValidationResult.Invalid,
            repository.validateBlob(store, null, "blobs/00/00/empty.zip"),
        )
        assertEquals(
            BackupBlobValidationResult.Invalid,
            repository.validateBlob(store, null, "blobs/00/00/not-a-zip.zip"),
        )
        assertEquals(
            BackupBlobValidationResult.Invalid,
            repository.validateBlob(store, null, "blobs/00/00/missing-entry.zip"),
        )
        assertEquals(
            BackupBlobValidationResult.Invalid,
            repository.validateBlob(store, Password("wrong-password"), encryptedPath),
        )
        assertEquals(
            BackupBlobValidationResult.Invalid,
            repository.validateBlob(store, null, truncatedPath),
        )
    }

    @Test
    fun `validateBlob returns unavailable for transient store read failures`() = runTest {
        val root = createTempDirectory("keyguard-backup-repository-transient-blob-validation").toLocalPath()
        val localStore = LocalFolderBackupObjectStore(root)
        val blobPath = "blobs/00/00/transient.zip"
        repository.writeBlob(
            store = localStore,
            objectPassword = null,
            blobPath = blobPath,
        ) { sink ->
            sink.write("payload".encodeToByteArray())
        }

        val store = TransientReadBackupObjectStore(
            delegate = localStore,
            transientKey = BackupObjectKey(blobPath),
        )

        assertEquals(
            BackupBlobValidationResult.Unavailable,
            repository.validateBlob(store, null, blobPath),
        )
    }

    @Test
    fun `writeSnapshot refuses conflicting existing snapshot`() = runTest {
        val root = createTempDirectory("keyguard-backup-repository-snapshot-collision").toLocalPath()
        val store = LocalFolderBackupObjectStore(root)
        val first = manifest(
            snapshotId = "snapshot-1",
            vaultSize = 1L,
        )
        val second = manifest(
            snapshotId = "snapshot-1",
            vaultSize = 2L,
        )

        repository.writeSnapshot(
            store = store,
            objectPassword = null,
            snapshotId = "snapshot-1",
            manifest = first,
            vaultJson = "{}",
        )

        assertFailsWith<BackupObjectStoreException.AlreadyExists> {
            repository.writeSnapshot(
                store = store,
                objectPassword = null,
                snapshotId = "snapshot-1",
                manifest = second,
                vaultJson = "{\"changed\":true}",
            )
        }
        assertEquals(first, repository.readSnapshotManifest(store, null, "snapshot-1"))
    }

    @Test
    fun `repository writes use create mode`() = runTest {
        val root = createTempDirectory("keyguard-backup-repository-write-modes").toLocalPath()
        val store = RepositoryWriteModeRecordingStore(LocalFolderBackupObjectStore(root))
        val now = Instant.fromEpochMilliseconds(1L)

        repository.getOrCreateMetadata(
            store = store,
            password = null,
            nowProvider = { now },
            repoIdProvider = { "repo-1" },
        )
        repository.writeIndex(
            store = store,
            password = null,
            index = index(indexId = "index-1", generation = 1L, updatedAt = now),
        )
        repository.writeBlob(
            store = store,
            objectPassword = null,
            blobPath = "blobs/ab/cd/abcdef.zip",
        ) { sink ->
            sink.write("attachment".encodeToByteArray())
        }
        repository.writeSnapshot(
            store = store,
            objectPassword = null,
            snapshotId = "snapshot-1",
            manifest = manifest("snapshot-1"),
            vaultJson = "{}",
        )

        assertTrue(store.writeModes.isNotEmpty())
        assertTrue(store.writeModes.all { (_, mode) -> mode == BackupWriteMode.Create })
    }

    private fun manifest(
        snapshotId: String,
        vaultSize: Long = 2L,
    ) = BackupSnapshotManifest(
        snapshotId = snapshotId,
        createdAt = Instant.fromEpochMilliseconds(1L),
        options = BackupSnapshotOptions(
            includeAttachments = true,
        ),
        vault = BackupSnapshotVault(
            size = vaultSize,
        ),
        attachments = emptyList(),
        stats = BackupSnapshotStats(
            cipherCount = 0,
            attachmentCount = 0,
            newBlobCount = 0,
            reusedBlobCount = 0,
        ),
    )

    private fun index(
        indexId: String,
        generation: Long,
        updatedAt: Instant,
    ) = BackupIndex(
        indexId = indexId,
        generation = generation,
        updatedAt = updatedAt,
    )

    private fun writeRepoMetadata(
        root: File,
        metadata: BackupRepositoryMetadata,
    ) {
        val repoFile = root.resolve("repo.zip")
        ZipOutputStream(repoFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("repo.json"))
            zip.write(json.encodeToString(metadata).encodeToByteArray())
            zip.closeEntry()
        }
    }

    private fun writeBytes(
        file: File,
        bytes: ByteArray,
    ) {
        requireNotNull(file.parentFile).mkdirs()
        file.writeBytes(bytes)
    }

    private fun writeZipEntry(
        file: File,
        entryName: String,
        bytes: ByteArray,
    ) {
        requireNotNull(file.parentFile).mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(bytes)
            zip.closeEntry()
        }
    }

}

private class TransientReadBackupObjectStore(
    private val delegate: BackupObjectStore,
    private val transientKey: BackupObjectKey,
) : BackupObjectStore by delegate {
    override suspend fun read(
        key: BackupObjectKey,
        range: BackupByteRange?,
    ): kotlinx.io.Source {
        if (key == transientKey) {
            throw BackupObjectStoreException.Transient(
                operation = BackupObjectStoreOperation.Read,
                key = key,
            )
        }
        return delegate.read(key, range)
    }
}

private class RepositoryWriteModeRecordingStore(
    private val delegate: BackupObjectStore,
) : BackupObjectStore by delegate {
    val writeModes = mutableListOf<Pair<BackupObjectKey, BackupWriteMode>>()

    override suspend fun write(
        key: BackupObjectKey,
        mode: BackupWriteMode,
        write: suspend (kotlinx.io.Sink) -> Unit,
    ): BackupObjectInfo {
        writeModes += key to mode
        return delegate.write(
            key = key,
            mode = mode,
            write = write,
        )
    }
}

private class PaginatedBackupObjectStore(
    private val delegate: BackupObjectStore,
    private val pageSize: Int = 1,
) : BackupObjectStore by delegate {
    override suspend fun list(
        prefix: BackupObjectKeyPrefix,
        cursor: BackupListCursor?,
    ): BackupObjectListPage {
        val startIndex = cursor
            ?.value
            ?.toInt()
            ?: 0
        val allItems = delegate
            .list(prefix)
            .items
        val pageItems = allItems
            .drop(startIndex)
            .take(pageSize)
        val nextIndex = startIndex + pageItems.size
        val nextCursor = BackupListCursor(nextIndex.toString())
            .takeIf { nextIndex < allItems.size }
        return BackupObjectListPage(
            items = pageItems,
            nextCursor = nextCursor,
        )
    }
}
