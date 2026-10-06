package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.file.FileAccessToken
import com.artemchep.keyguard.platform.appleBookmarkCreationOptions
import com.artemchep.keyguard.platform.toSecurityScopedBookmarkToken
import com.artemchep.keyguard.util.zip.createZipService
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.posix.symlink
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalForeignApi::class)
class AppleFolderBackupObjectStoreTest {
    private val factory = AppleFolderBackupObjectStoreFactory()
    private val key = BackupObjectKey("snapshots/test.zip")

    @Test
    fun fullProbeAndStreamingRanges() = runTest {
        withAppleBackupFolder { _, store ->
            assertTrue(store.test().listed)
            val bytes = ByteArray(256 * 1024 + 7) { (it % 251).toByte() }
            store.write(key) { it.write(bytes) }
            assertEquals(bytes.size.toLong(), store.stat(key)?.size)
            assertContentEquals(bytes, store.read(key).use { it.readByteArray() })
            assertContentEquals(
                bytes.copyOfRange(65530, 65555),
                store.read(key, BackupByteRange(65530, 25)).use { it.readByteArray() },
            )
            assertEquals(0, store.read(key, BackupByteRange(bytes.size.toLong())).use { it.readByteArray().size })
            assertFailsWith<BackupObjectStoreException.InvalidRange> {
                store.read(key, BackupByteRange(bytes.size + 1L))
            }
            assertFailsWith<BackupObjectStoreException.InvalidRange> {
                store.read(key, BackupByteRange(1L, Long.MAX_VALUE))
            }
            assertEquals(listOf(key), store.list(BackupObjectKeyPrefix("snapshots/")).items.map { it.key })
            store.delete(key)
            store.delete(key)
            assertNull(store.stat(key))
            assertFailsWith<BackupObjectStoreException.NotFound> { store.read(key) }
        }
    }

    @Test
    fun createCollisionAndFailedReplacementPreservePreviousBytes() = runTest {
        withAppleBackupFolder { _, store ->
            store.write(key) { it.writeString("old") }
            assertFailsWith<BackupObjectStoreException.AlreadyExists> {
                store.write(key, BackupWriteMode.Create) { it.writeString("new") }
            }
            assertFailsWith<CancellationException> {
                store.write(key) {
                    it.writeString("partial")
                    throw CancellationException("cancel")
                }
            }
            assertEquals("old", store.read(key).use { it.readString() })
            assertEquals(listOf(key), store.list(BackupObjectKeyPrefix("")).items.map { it.key })
            store.write(key) { it.writeString("replacement") }
            assertEquals("replacement", store.read(key).use { it.readString() })
        }
    }

    @Test
    fun readSourceSurvivesStoreClose() = runTest {
        withAppleBackupFolder { _, store ->
            store.write(key) { it.writeString("content") }
            val source = store.read(key)
            store.close()
            assertEquals("content", source.use { it.readString() })
            store.close()
        }
    }

    @Test
    fun symlinkCannotReadWriteOrDeleteOutsideRoot() = runTest {
        withAppleBackupFolder { root, store ->
            val outside = createAppleBackupFolder()
            try {
                val victim = Path(outside, "victim")
                SystemFileSystem.sink(victim).buffered().use { it.writeString("untouched") }
                assertEquals(0, symlink(outside, "$root/link"))
                assertEquals(0, symlink(victim.toString(), "$root/victim"))
                val escaped = BackupObjectKey("link/victim")
                assertFailsWith<BackupObjectStoreException> { store.read(escaped) }
                assertFailsWith<Exception> { store.write(escaped) { it.writeString("wrong") } }
                assertFailsWith<BackupObjectStoreException> { store.delete(escaped) }
                assertFailsWith<BackupObjectStoreException> { store.read(BackupObjectKey("victim")) }
                assertTrue(store.list(BackupObjectKeyPrefix("")).items.isEmpty())
                store.delete(BackupObjectKey("victim"))
                assertEquals("untouched", SystemFileSystem.source(victim).buffered().use { it.readString() })
            } finally { removeAppleBackupFolder(outside) }
        }
    }

    @Test
    fun bookmarkReopensDirectoryAndInvalidTokenDoesNotFallBackToPath() = runTest {
        val root = createAppleBackupFolder()
        try {
            val config = bookmarkConfig(root)
            factory.useStore(config) { store -> store.write(key) { it.writeString("saved") } }
            factory.useStore(config) { store ->
                assertEquals("saved", store.read(key).use { it.readString() })
            }
            assertFailsWith<BackupObjectStoreException.PermissionDenied> {
                factory.open(config.copy(accessToken = FileAccessToken("invalid bookmark")))
            }
        } finally { removeAppleBackupFolder(root) }
    }

    @Test
    fun bookmarkFollowsRenamedDirectoryAndRefreshesSavedLocation() = runTest {
        val root = createAppleBackupFolder()
        val moved = "$root-moved"
        try {
            val original = bookmarkConfig(root)
            assertTrue(NSFileManager.defaultManager.moveItemAtPath(root, moved, null))
            var updated: BackupStoreConfig.Local? = null
            val refreshingFactory = AppleFolderBackupObjectStoreFactory { expected, refreshed ->
                assertEquals(original, expected)
                updated = refreshed
            }
            refreshingFactory.useStore(original) { store -> store.write(key) { it.writeString("moved") } }
            assertEquals("moved", SystemFileSystem.source(Path(moved, key.value)).buffered().use { it.readString() })
            val refreshed = assertNotNull(updated)
            assertNotEquals(original.path, refreshed.path)
            factory.useStore(refreshed) { store ->
                assertEquals("moved", store.read(key).use { it.readString() })
            }
        } finally {
            removeAppleBackupFolder(root)
            removeAppleBackupFolder(moved)
        }
    }

    @Test
    fun encryptedAndPlainRepositoryMetadataAndBlobsRoundTrip() = runTest {
        for (password in listOf(null, Password("backup-password"))) {
            withAppleBackupFolder { _, store ->
                val repository = BackupRepositoryZipImpl(
                    Json {
                        encodeDefaults = true
                        ignoreUnknownKeys = true
                    },
                    createZipService(),
                )
                val now = Instant.fromEpochMilliseconds(1234)
                val metadata = repository.getOrCreateMetadata(store, password, { now }, { "repo-1" })
                assertEquals(metadata, repository.getOrCreateMetadata(store, password, { now }, { "repo-2" }))
                val index = BackupIndex(indexId = "index-1", generation = 1, updatedAt = now)
                repository.writeIndex(store, password, index)
                assertEquals(listOf(index), repository.readIndexes(store, password))
                val blobPath = "blobs/ab/attachment.zip"
                val bytes = ByteArray(128 * 1024 + 5) { (it % 251).toByte() }
                repository.writeBlob(store, password, blobPath) { it.write(bytes) }
                assertTrue(repository.hasBlob(store, blobPath))
                assertEquals(BackupBlobValidationResult.Valid, repository.validateBlob(store, password, blobPath))
                if (password != null) {
                    assertEquals(
                        BackupBlobValidationResult.Invalid,
                        repository.validateBlob(store, Password("wrong"), blobPath),
                    )
                }
                val manifest = BackupSnapshotManifest(
                    snapshotId = "snapshot-1",
                    createdAt = now,
                    options = BackupSnapshotOptions(includeAttachments = true),
                    vault = BackupSnapshotVault(size = 2),
                    attachments = emptyList(),
                    stats = BackupSnapshotStats(
                        cipherCount = 0,
                        attachmentCount = 0,
                        newBlobCount = 0,
                        reusedBlobCount = 0,
                    ),
                )
                repository.writeSnapshot(store, password, "snapshot-1", manifest, "{}")
                assertEquals(listOf("snapshot-1"), repository.listSnapshotIds(store))
                assertEquals(manifest, repository.readSnapshotManifest(store, password, "snapshot-1"))
                repository.deleteSnapshot(store, "snapshot-1")
                repository.deleteBlob(store, blobPath)
                assertTrue(repository.listSnapshotIds(store).isEmpty())
                assertFalse(repository.hasBlob(store, blobPath))
            }
        }
    }

    private fun bookmarkConfig(root: String): BackupStoreConfig.Local {
        val url = NSURL.fileURLWithPath(root)
        val data = assertNotNull(url.bookmarkDataWithOptions(appleBookmarkCreationOptions, null, null, null))
        return BackupStoreConfig.Local(url.absoluteString, FileAccessToken(data.toSecurityScopedBookmarkToken()))
    }
}
