package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.util.age.AgePassword
import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.archive.writeAgeArchive
import com.artemchep.keyguard.util.io.toLocalPath
import com.artemchep.keyguard.util.io.toSource
import com.artemchep.keyguard.util.zip.ZipConfig
import com.artemchep.keyguard.util.zip.ZipEntry
import com.artemchep.keyguard.util.zip.ZipReader
import com.artemchep.keyguard.util.zip.createZipService
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.io.Sink
import kotlinx.io.readString

class BackupAgeMigrationTest : BackupAgeTestBase() {
    @Test
    fun upgradesOnlyRepositoryMetadataAndPreservesAllArchives() = runBlocking {
        withLegacy { root, store ->
            val second = index("second", 2L, now)
            repository.writeIndex(store, password, second)
            val snapshot = root.resolve("snapshots/unchanged.zip")
            snapshot.parentFile.mkdirs()
            snapshot.writeBytes(byteArrayOf(1, 2, 3))
            // Include an unrecognized legacy archive: upgrading must leave
            // all of them byte-for-byte intact.
            root.resolve("indexes/old-index.zip").writeText("legacy archive")
            val originals = root.walkTopDown().filter { it.isFile && it.name != "repo.zip" }
                .associate { it.relativeTo(root).path to it.readBytes() }
            repository.upgradeEncryption(store, password)
            assertEquals(2, repository.readMetadata(store, password)?.formatVersion)
            assertEquals(listOf(second), repository.readIndexes(store, password))
            originals.forEach { (path, bytes) -> assertContentEquals(bytes, root.resolve(path).readBytes(), path) }
            assertFalse(root.resolve("indexes/00000000000000000002-second.zip.age").exists())
            assertMarker(store)
            assertFalse(root.resolve(BACKUP_AGE_MIGRATION_FILE).exists())
            repository.upgradeEncryption(store, password) // Idempotent after cleanup.
            repository.writeIndex(store, password, index("third", 3L, now))
            assertTrue(root.resolve("indexes/00000000000000000003-third.zip.age").exists())
            repository.getOrCreateMetadata(store, password, { now }, { error("Already exists") })
        }
    }

    @Test
    fun resumesAfterPublicationAndCleanupFailures() = runBlocking {
        // Each failure occurs after the store mutation, like a lost response.
        for (failure in listOf(
            "write:$BACKUP_AGE_MIGRATION_FILE", "write:repo.zip.age", "write:repo.zip",
            "delete:$BACKUP_AGE_MIGRATION_FILE",
        )) {
            withLegacy { root, store ->
                val original = root.resolve(oldIndexPath).readBytes()
                var armed = true
                val failing = object : BackupObjectStore by store {
                    override suspend fun write(
                        key: BackupObjectKey,
                        mode: BackupWriteMode,
                        write: suspend (Sink) -> Unit,
                    ): BackupObjectInfo {
                        val info = store.write(key, mode, write)
                        if (armed && failure == "write:${key.value}") {
                            armed = false
                            error("Injected publication failure")
                        }
                        return info
                    }
                    override suspend fun delete(key: BackupObjectKey) {
                        store.delete(key)
                        if (armed && failure == "delete:${key.value}") {
                            armed = false
                            error("Injected cleanup failure")
                        }
                    }
                }
                assertFails(failure) { repository.upgradeEncryption(failing, password) }
                assertContentEquals(original, root.resolve(oldIndexPath).readBytes(), failure)
                if (root.resolve(BACKUP_AGE_MIGRATION_FILE).exists()) {
                    assertFails { repository.getOrCreateMetadata(store, password, { now }, { "new" }) }
                    assertFails { repository.writeIndex(store, password, index("blocked", 3L, now)) }
                }
                repository.upgradeEncryption(store, password)
                assertEquals(listOf(index("first", 1L, now)), repository.readIndexes(store, password), failure)
                assertContentEquals(original, root.resolve(oldIndexPath).readBytes(), failure)
                assertMarker(store)
                assertFalse(root.resolve(BACKUP_AGE_MIGRATION_FILE).exists(), failure)
            }
        }
    }

    @Test
    fun tornJournalAndMetadataAreRecoverableWithoutLosingIndexes() = runBlocking {
        for (path in listOf(BACKUP_AGE_MIGRATION_FILE, "repo.zip.age", "repo.zip")) {
            withLegacy { root, store ->
                val original = root.resolve(oldIndexPath).readBytes()
                val failing = object : BackupObjectStore by store {
                    override suspend fun write(
                        key: BackupObjectKey,
                        mode: BackupWriteMode,
                        write: suspend (Sink) -> Unit,
                    ): BackupObjectInfo {
                        val info = store.write(key, mode, write)
                        if (key.value == path) {
                            val file = root.resolve(path)
                            file.writeBytes(file.readBytes().take(30).toByteArray())
                            error("Torn write")
                        }
                        return info
                    }
                }
                assertFails { repository.upgradeEncryption(failing, password) }
                assertContentEquals(original, root.resolve(oldIndexPath).readBytes())
                repository.upgradeEncryption(store, password)
                assertEquals(listOf(index("first", 1L, now)), repository.readIndexes(store, password))
                assertContentEquals(original, root.resolve(oldIndexPath).readBytes())
                assertMarker(store)
            }
        }
    }

    @Test
    fun damagedJournalAfterCommitStillFinishesTheUpgrade() = runBlocking {
        withLegacy { root, store ->
            val original = root.resolve(oldIndexPath).readBytes()
            repository.interruptBeforeMarker(store, password)
            assertTrue(root.resolve("repo.zip.age").exists())
            val journal = root.resolve(BACKUP_AGE_MIGRATION_FILE)
            journal.writeBytes(journal.readBytes().take(30).toByteArray())
            assertFails { repository.getOrCreateMetadata(store, password, { now }, { "new" }) }

            repository.upgradeEncryption(store, password)

            assertFalse(journal.exists())
            assertMarker(store)
            assertEquals(2, repository.readMetadata(store, password)?.formatVersion)
            assertEquals(listOf(index("first", 1L, now)), repository.readIndexes(store, password))
            assertContentEquals(original, root.resolve(oldIndexPath).readBytes())
            repository.getOrCreateMetadata(store, password, { now }, { error("Already exists") })
        }
    }

    @Test
    fun damagedJournalWithWrongPasswordChangesNothing() = runBlocking {
        withLegacy { root, store ->
            repository.interruptBeforeMarker(store, password)
            val journal = root.resolve(BACKUP_AGE_MIGRATION_FILE)
            journal.writeBytes(journal.readBytes().take(30).toByteArray())
            val legacy = root.resolve("repo.zip").readBytes()

            assertFails { repository.upgradeEncryption(store, Password("wrong")) }

            assertTrue(journal.exists())
            assertContentEquals(legacy, root.resolve("repo.zip").readBytes())
        }
    }

    @Test
    fun verificationFailureKeepsOriginalMetadataAndIndexes() = runBlocking {
        withLegacy { root, store ->
            val originalRepo = root.resolve("repo.zip").readBytes()
            val originalIndex = root.resolve(oldIndexPath).readBytes()
            val corrupting = object : BackupObjectStore by store {
                override suspend fun write(
                    key: BackupObjectKey,
                    mode: BackupWriteMode,
                    write: suspend (Sink) -> Unit,
                ): BackupObjectInfo {
                    val info = store.write(key, mode, write)
                    if (key.value == "repo.zip.age") {
                        val file = root.resolve(key.value)
                        val bytes = file.readBytes()
                        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                        file.writeBytes(bytes)
                    }
                    return info
                }
            }
            assertFails { repository.upgradeEncryption(corrupting, password) }
            assertContentEquals(originalRepo, root.resolve("repo.zip").readBytes())
            assertContentEquals(originalIndex, root.resolve(oldIndexPath).readBytes())
            repository.upgradeEncryption(store, password)
            assertEquals(listOf(index("first", 1L, now)), repository.readIndexes(store, password))
        }
    }

    @Test
    fun doesNotOverwriteReplacementEncryptedWithAnotherPassword() = runBlocking {
        withLegacy { root, store ->
            val interrupting = object : BackupObjectStore by store {
                override suspend fun write(
                    key: BackupObjectKey,
                    mode: BackupWriteMode,
                    write: suspend (Sink) -> Unit,
                ): BackupObjectInfo {
                    store.write(key, mode, write)
                    error("Interrupted after journal publication")
                }
            }
            assertFails { repository.upgradeEncryption(interrupting, password) }
            store.write(BackupObjectKey("repo.zip.age"), BackupWriteMode.Create) { sink ->
                writeAgeArchive(sink, AgePassword("another password"), listOf(
                    ZipEntry("repo.json", data = ZipEntry.Data.In { "{}".toSource() }),
                ))
            }
            val replacement = root.resolve("repo.zip.age").readBytes()
            assertFails { repository.upgradeEncryption(store, password) }
            assertContentEquals(replacement, root.resolve("repo.zip.age").readBytes())
            assertTrue(root.resolve(oldIndexPath).exists())
        }
    }

    @Test
    fun wrongPasswordLeavesLegacyFilesUntouched() = runBlocking {
        withLegacy { root, store ->
            val repo = root.resolve("repo.zip").readBytes()
            val index = root.resolve(oldIndexPath).readBytes()
            assertFails { repository.upgradeEncryption(store, Password("wrong")) }
            assertContentEquals(repo, root.resolve("repo.zip").readBytes())
            assertContentEquals(index, root.resolve(oldIndexPath).readBytes())
            assertFalse(root.resolve(BACKUP_AGE_MIGRATION_FILE).exists())
        }
    }

    @Test
    fun corruptedAgeRepositoryNeverFallsBackToLegacyMetadata() = runBlocking {
        withLegacy { root, store ->
            root.resolve("repo.zip.age").writeText("damaged")
            assertFails { repository.readMetadata(store, password) }
            assertFails { repository.getOrCreateMetadata(store, password, { now }, { "new" }) }
        }
    }

    @Test
    fun newRepositoryIndexesUseItsKeyInsteadOfThePassword() = runBlocking {
        val root = createTempDirectory("keyguard-age-index-key").toFile()
        try {
            val store = LocalFolderBackupObjectStore(root.toLocalPath())
            val metadata = repository.getOrCreateMetadata(store, password, { now }, { "new" })
            val key = assertNotNull(metadata.keys).index
            assertTrue(key.value.startsWith("AGE-SECRET-KEY-1"))
            assertFalse(key.value in metadata.toString(), "the key is never printed")
            assertMarker(store)

            val index = index("first", 1L, now)
            repository.writeIndex(store, metadata, password, index)
            val header = root.resolve(indexPath(index, age = true)).readBytes()
                .decodeToString().lineSequence().take(2).toList()
            assertEquals("age-encryption.org/v1", header[0])
            assertTrue(header[1].startsWith("-> X25519 "), "no scrypt stanza: ${header[1]}")
            val reopened = assertNotNull(repository.readMetadata(store, password))
            assertEquals(metadata, reopened)
            assertEquals(listOf(index), repository.readIndexes(store, reopened, password))
        } finally {
            root.deleteRecursively()
        }
    }

    private suspend fun assertMarker(store: BackupObjectStore) {
        ZipReader(store.read(BackupObjectKey("repo.zip")), null).use { reader ->
            val text = assertNotNull(reader.nextEntry()).source.readString()
            val marker = json.decodeFromString<BackupRepositoryMetadata>(text)
            assertEquals(listOf("age-repository-marker"), marker.features)
            assertEquals("zip-none", marker.crypto.archive)
            assertEquals(null, marker.keys, "the unencrypted marker never holds keys")
            assertFails { validateBackupRepositoryFormat(marker) }
        }
    }
}

abstract class BackupAgeTestBase : BackupRepositoryZipTestBase() {
    protected val password = Password("migration test password")
    protected val now = Instant.fromEpochMilliseconds(1L)
    protected val oldIndexPath = "indexes/00000000000000000001-first.zip"

    protected fun indexPath(index: BackupIndex, age: Boolean = false): String =
        "indexes/${index.generation.toString().padStart(20, '0')}-${index.indexId}.zip" + if (age) ".age" else ""

    protected suspend fun writeIndexArchive(store: BackupObjectStore, index: BackupIndex, age: Boolean) {
        val path = indexPath(index, age)
        val text = json.encodeToString(index)
        if (age) {
            writeAge(store, path, "index.json", text)
        } else {
            store.write(BackupObjectKey(path), BackupWriteMode.Create) { sink ->
                createZipService().zip(sink, ZipConfig(encryption = ZipConfig.Encryption(password.value)), listOf(
                    ZipEntry("index.json", data = ZipEntry.Data.In { text.toSource() }),
                ))
            }
        }
    }

    /** Writes an archive encrypted to the upgraded repository's index key. */
    protected suspend fun writeAge(store: BackupObjectStore, path: String, entry: String, text: String) {
        val identity = assertNotNull(repository.readMetadata(store, password)?.indexIdentity)
        store.write(BackupObjectKey(path), BackupWriteMode.Create) { sink ->
            writeAgeArchive(sink, identity, listOf(
                ZipEntry(entry, data = ZipEntry.Data.In { text.toSource() }),
            ))
        }
    }

    protected suspend fun withLegacy(block: suspend (File, BackupObjectStore) -> Unit) {
        val root = createTempDirectory("keyguard-age-migration").toFile()
        val store = LocalFolderBackupObjectStore(root.toLocalPath())
        try {
            val metadata = BackupRepositoryMetadata(repoId = "legacy", createdAt = now)
            store.write(BackupObjectKey("repo.zip"), BackupWriteMode.Create) { sink ->
                createZipService().zip(sink, ZipConfig(encryption = ZipConfig.Encryption(password.value)), listOf(
                    ZipEntry("repo.json", data = ZipEntry.Data.In { json.encodeToString(metadata).toSource() }),
                ))
            }
            repository.writeIndex(store, password, index("first", 1L, now))
            block(root, store)
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }
}

/** Publishes `repo.zip.age`, then fails before `repo.zip` becomes the marker. */
private suspend fun BackupRepository.interruptBeforeMarker(store: BackupObjectStore, password: Password) {
    val failing = object : BackupObjectStore by store {
        override suspend fun write(
            key: BackupObjectKey,
            mode: BackupWriteMode,
            write: suspend (Sink) -> Unit,
        ): BackupObjectInfo {
            if (key.value == "repo.zip") error("Interrupted before the marker")
            return store.write(key, mode, write)
        }
    }
    assertFails { upgradeEncryption(failing, password) }
}
