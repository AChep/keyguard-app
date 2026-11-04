package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.service.archive.writeAgeArchive
import com.artemchep.keyguard.util.age.AgePassword
import com.artemchep.keyguard.util.io.toLocalPath
import com.artemchep.keyguard.util.io.toSource
import com.artemchep.keyguard.util.zip.ZipEntry
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlinx.coroutines.runBlocking
import kotlinx.io.Source

/** Every backup run opens the repository; on S3 or WebDAV each call is a round trip. */
class BackupRepositoryMetadataRequestsTest : BackupAgeTestBase() {
    @Test
    fun legacyRepositoryOpensWithoutLookups() = runBlocking {
        withLegacy { _, store ->
            val counting = CountingStore(store)

            repository.getOrCreateMetadata(counting, password, { now }, { error("Already exists") })

            assertEquals(
                listOf(
                    "stat:$BACKUP_AGE_MIGRATION_FILE",
                    "read:$BACKUP_AGE_REPO_FILE",
                    "read:$BACKUP_REPO_FILE",
                ),
                counting.calls,
            )
        }
    }

    @Test
    fun ageRepositoryOpensWithOneMetadataRead() = runBlocking {
        val root = createTempDirectory("keyguard-age-requests").toFile()
        try {
            val store = LocalFolderBackupObjectStore(root.toLocalPath())
            repository.getOrCreateMetadata(store, password, { now }, { "new" })
            val counting = CountingStore(store)

            repository.getOrCreateMetadata(counting, password, { now }, { error("Already exists") })

            assertEquals(
                listOf(
                    "stat:$BACKUP_AGE_MIGRATION_FILE",
                    "read:$BACKUP_AGE_REPO_FILE",
                    // The compatibility marker check.
                    "stat:$BACKUP_REPO_FILE",
                    "read:$BACKUP_REPO_FILE",
                ),
                counting.calls,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ageArchiveWithoutMetadataNeverFallsBackToLegacy() = runBlocking {
        withLegacy { _, store ->
            store.write(BackupObjectKey(BACKUP_AGE_REPO_FILE), BackupWriteMode.Create) { sink ->
                writeAgeArchive(sink, AgePassword(password.value), listOf(
                    ZipEntry("other.json", data = ZipEntry.Data.In { "{}".toSource() }),
                ))
            }

            assertFails { repository.readMetadata(store, password) }
        }
    }

    private class CountingStore(
        private val store: BackupObjectStore,
    ) : BackupObjectStore by store {
        val calls = mutableListOf<String>()

        override suspend fun stat(key: BackupObjectKey): BackupObjectInfo? {
            calls += "stat:${key.value}"
            return store.stat(key)
        }

        override suspend fun read(key: BackupObjectKey, range: BackupByteRange?): Source {
            calls += "read:${key.value}"
            return store.read(key, range)
        }
    }
}
