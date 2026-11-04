package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.util.zip.ZipEncryptionRequiredException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class BackupRepositoryMixedIndexTest : BackupAgeTestBase() {
    @Test
    fun readsNewestLegacyGenerationBeforeAndAfterUpgrade() = runBlocking {
        withLegacy { _, store ->
            val newest = index("newest", 2L, now)
            repository.writeIndex(store, password, newest)
            assertEquals(listOf(newest), repository.readIndexes(store, password))
            repository.upgradeEncryption(store, password)
            assertEquals(listOf(newest), repository.readIndexes(store, password))
        }
    }

    @Test
    fun newAgeGenerationSupersedesAllOlderLegacyHeads() = runBlocking {
        withLegacy { _, store ->
            repository.writeIndex(store, password, index("concurrent", 1L, now))
            repository.upgradeEncryption(store, password)
            val newest = index("retention", 2L, now)
            repository.writeIndex(store, password, newest)
            assertEquals(listOf(newest), repository.readIndexes(store, password))
        }
    }

    @Test
    fun combinesMixedHeadsInTheSameGenerationAcrossListingPages() = runBlocking {
        withLegacy { _, store ->
            val legacy = index("head", 2L, now)
            writeIndexArchive(store, legacy, age = false)
            repository.upgradeEncryption(store, password)
            val modern = index("head-other", 2L, now)
            repository.writeIndex(store, password, modern)
            val paginated = object : BackupObjectStore by store {
                override suspend fun list(
                    prefix: BackupObjectKeyPrefix,
                    cursor: BackupListCursor?,
                ): BackupObjectListPage {
                    val items = store.list(prefix).items
                    val offset = cursor?.value?.toInt() ?: 0
                    return BackupObjectListPage(
                        items = items.drop(offset).take(1),
                        nextCursor = if (offset + 1 < items.size) BackupListCursor((offset + 1).toString()) else null,
                    )
                }
            }
            assertEquals(listOf(legacy, modern), repository.readIndexes(paginated, password))
        }
    }

    @Test
    fun ageCopyIsAuthoritativeForDuplicateIndexIdentity() = runBlocking {
        withLegacy { _, store ->
            repository.upgradeEncryption(store, password)
            val preferred = index("first", 1L, Instant.fromEpochMilliseconds(2L))
            writeIndexArchive(store, preferred, age = true)
            assertEquals(listOf(preferred), repository.readIndexes(store, password))
        }
    }

    @Test
    fun corruptAgeCopyNeverFallsBackToItsLegacyTwin() = runBlocking {
        withLegacy { root, store ->
            repository.upgradeEncryption(store, password)
            root.resolve("$oldIndexPath.age").writeText("damaged")
            assertFailsWith<IllegalStateException> { repository.readIndexes(store, password) }
        }
    }

    @Test
    fun invalidAgeIndexIdentityNeverFallsBackToItsLegacyTwin() = runBlocking {
        withLegacy { _, store ->
            repository.upgradeEncryption(store, password)
            writeAge(store, "$oldIndexPath.age", "index.json", json.encodeToString(index("another", 1L, now)))
            assertFailsWith<IllegalStateException> { repository.readIndexes(store, password) }
        }
    }

    @Test
    fun corruptNewerGenerationCanStillFallBackToOlderReadableGeneration() = runBlocking {
        withLegacy { root, store ->
            val newest = index("newest", 2L, now)
            repository.writeIndex(store, password, newest)
            repository.upgradeEncryption(store, password)
            root.resolve(indexPath(newest, age = true)).writeText("damaged")
            assertEquals(listOf(index("first", 1L, now)), repository.readIndexes(store, password))
        }
    }

    @Test
    fun mixedRepositoryRejectsPlaintextLegacyIndex() = runBlocking {
        withLegacy { root, store ->
            repository.upgradeEncryption(store, password)
            val forged = index("forged", 2L, now)
            writeZipEntry(
                root.resolve(indexPath(forged)),
                "index.json",
                json.encodeToString(forged).encodeToByteArray(),
            )
            assertFailsWith<ZipEncryptionRequiredException> { repository.readIndexes(store, password) }
        }
    }
}
