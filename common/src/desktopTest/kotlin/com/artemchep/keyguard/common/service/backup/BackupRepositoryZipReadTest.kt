package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.util.io.toLocalPath
import com.artemchep.keyguard.util.zip.ZipPasswordException
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlinx.io.readString
import java.io.File
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Instant

@Suppress("FunctionNaming")
class BackupRepositoryZipReadTest : BackupRepositoryZipTestBase() {
    private val now = Instant.fromEpochMilliseconds(1L)

    @Test
    fun `reads a snapshot and a blob back with their own passwords`() = runTest {
        val store = LocalFolderBackupObjectStore(createTempDirectory("keyguard-backup-read").toLocalPath())
        val snapshotPassword = Password("snapshot-password")
        val blobPassword = Password("blob-password")
        val manifest = manifest(snapshotId = "snapshot-1")
        repository.writeSnapshot(
            store = store,
            objectPassword = snapshotPassword,
            snapshotId = "snapshot-1",
            manifest = manifest,
            vaultJson = """{"items":[]}""",
        )
        val data = "attachment".encodeToByteArray()
        repository.writeBlob(
            store = store,
            objectPassword = blobPassword,
            blobPath = "blobs/ab/cd/abcdef.zip",
        ) { sink ->
            sink.write(data)
        }

        val snapshot = repository.readSnapshot(
            store = store,
            objectPassword = snapshotPassword,
            snapshotPath = "snapshots/snapshot-1.zip",
        ) { snapshotManifest, vault ->
            snapshotManifest to vault.readString()
        }
        assertEquals(manifest to """{"items":[]}""", snapshot)

        val blob = repository.readBlob(
            store = store,
            objectPassword = blobPassword,
            blobPath = "blobs/ab/cd/abcdef.zip",
        ) { source ->
            source.readByteArray()
        }
        assertContentEquals(data, blob)

        assertFailsWith<ZipPasswordException> {
            repository.readBlob(
                store = store,
                objectPassword = snapshotPassword,
                blobPath = "blobs/ab/cd/abcdef.zip",
            ) { source ->
                source.readByteArray()
            }
        }
    }

    @Test
    fun `missing snapshot and blob read as null`() = runTest {
        val store = LocalFolderBackupObjectStore(createTempDirectory("keyguard-backup-read").toLocalPath())

        assertNull(
            repository.readSnapshot(
                store = store,
                objectPassword = null,
                snapshotPath = "snapshots/missing.zip",
            ) { _, _ -> Unit },
        )
        assertNull(
            repository.readBlob(
                store = store,
                objectPassword = null,
                blobPath = "blobs/ab/cd/missing.zip",
            ) { _ -> Unit },
        )
    }

    @Test
    fun `a snapshot read starts over after a transient failure inside the vault`() = runTest {
        val localStore = LocalFolderBackupObjectStore(createTempDirectory("keyguard-backup-read").toLocalPath())
        val password = Password("snapshot-password")
        // Incompressible, so that the vault fills most of the archive.
        val vaultJson = """{"padding":"${Base64.getEncoder().encodeToString(Random(0).nextBytes(16_384))}"}"""
        repository.writeSnapshot(
            store = localStore,
            objectPassword = password,
            snapshotId = "snapshot-1",
            manifest = manifest(snapshotId = "snapshot-1"),
            vaultJson = vaultJson,
        )
        val key = BackupObjectKey("snapshots/snapshot-1.zip")
        val store = MidstreamTransientBackupObjectStore(
            delegate = localStore,
            transientKey = key,
            // Past the manifest, inside the vault.
            failAfterBytes = requireNotNull(localStore.stat(key)?.size) / 2,
        )

        var calls = 0
        val vault = repository.readSnapshot(
            store = store,
            objectPassword = password,
            snapshotPath = key.value,
        ) { _, source ->
            calls += 1
            source.readString()
        }

        assertEquals(vaultJson, vault)
        assertEquals(2, calls)
        assertEquals(2, store.reads)
    }

    @Test
    fun `a blob read starts over after a transient failure inside the blob`() = runTest {
        val localStore = LocalFolderBackupObjectStore(createTempDirectory("keyguard-backup-read").toLocalPath())
        val data = Random(0).nextBytes(16_384)
        val blobPath = "blobs/ab/cd/abcdef.zip"
        repository.writeBlob(
            store = localStore,
            objectPassword = null,
            blobPath = blobPath,
        ) { sink ->
            sink.write(data)
        }
        val key = BackupObjectKey(blobPath)
        val store = MidstreamTransientBackupObjectStore(
            delegate = localStore,
            transientKey = key,
            failAfterBytes = requireNotNull(localStore.stat(key)?.size) / 2,
        )

        var calls = 0
        val blob = repository.readBlob(
            store = store,
            objectPassword = null,
            blobPath = blobPath,
        ) { source ->
            calls += 1
            source.readByteArray()
        }

        assertContentEquals(data, blob)
        assertEquals(2, calls)
        assertEquals(2, store.reads)
    }

    @Test
    fun `a snapshot read does not fail on a transient lookup error`() = runTest {
        val localStore = LocalFolderBackupObjectStore(createTempDirectory("keyguard-backup-read").toLocalPath())
        repository.writeSnapshot(
            store = localStore,
            objectPassword = null,
            snapshotId = "snapshot-1",
            manifest = manifest(snapshotId = "snapshot-1"),
            vaultJson = """{"items":[]}""",
        )
        val store = object : BackupObjectStore by localStore {
            override suspend fun stat(
                key: BackupObjectKey,
            ): BackupObjectInfo? = throw BackupObjectStoreException.Transient(
                operation = BackupObjectStoreOperation.Stat,
                key = key,
            )
        }

        val vault = repository.readSnapshot(
            store = store,
            objectPassword = null,
            snapshotPath = "snapshots/snapshot-1.zip",
        ) { _, source ->
            source.readString()
        }

        assertEquals("""{"items":[]}""", vault)
    }

    @Test
    fun `reading metadata never creates a repository`() = runTest {
        val root = createTempDirectory("keyguard-backup-read")
        val store = LocalFolderBackupObjectStore(root.toLocalPath())

        assertNull(repository.readMetadata(store, password = null))
        assertFalse(File(root.toString()).resolve("repo.zip").exists())
    }

    @Test
    fun `reads unencrypted metadata without a password`() = runTest {
        val store = LocalFolderBackupObjectStore(createTempDirectory("keyguard-backup-read").toLocalPath())
        val metadata = repository.getOrCreateMetadata(
            store = store,
            password = null,
            nowProvider = { now },
            repoIdProvider = { "repo-1" },
        )

        assertEquals(metadata, repository.readMetadata(store, password = null))
    }

    @Test
    fun `reading encrypted metadata needs the right password`() = runTest {
        val store = LocalFolderBackupObjectStore(createTempDirectory("keyguard-backup-read").toLocalPath())
        val password = Password("backup-password")
        val metadata = repository.getOrCreateMetadata(
            store = store,
            password = password,
            nowProvider = { now },
            repoIdProvider = { "repo-1" },
        )

        assertFailsWith<ZipPasswordException> {
            repository.readMetadata(store, password = null)
        }
        assertFailsWith<ZipPasswordException> {
            repository.readMetadata(store, password = Password("wrong-password"))
        }
        assertEquals(metadata, repository.readMetadata(store, password))
    }
}
