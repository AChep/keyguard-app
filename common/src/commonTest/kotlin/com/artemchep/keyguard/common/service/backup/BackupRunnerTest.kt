package com.artemchep.keyguard.common.service.backup

import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DownloadAttachmentRequest
import com.artemchep.keyguard.common.model.DownloadAttachmentRequestData
import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.download.DownloadTask
import com.artemchep.keyguard.common.service.download.DownloadWriter
import com.artemchep.keyguard.common.service.download.writeBytes
import com.artemchep.keyguard.common.service.export.ExportVaultData
import com.artemchep.keyguard.common.service.export.ExportVaultDataService
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.DownloadAttachmentMetadata
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.readByteArray

class BackupRunnerTest {
    @Test
    fun `attachment decryption failure is classified`() = runTest {
        val cause = RuntimeException(
            "Error finalising cipher",
            RuntimeException("Message authentication codes do not match!"),
        )
        val runner = runner(
            repository = MemoryBackupRepository(),
            downloadTask = CountingDownloadTask(failure = cause),
        )

        val error = assertFailsWith<BackupException.AttachmentDecryption> {
            runner.run(config())
        }

        assertEquals(false, error.retryable)
        assertEquals("local-cipher-1", error.localCipherId)
        assertEquals("remote-cipher-1", error.remoteCipherId)
        assertEquals("attachment-1", error.attachmentId)
        assertEquals(cause, error.cause)
    }

    @Test
    fun `generic attachment download failure is not classified as decryption`() = runTest {
        val cause = RuntimeException("Attachment download failed.")
        val runner = runner(
            repository = MemoryBackupRepository(),
            downloadTask = CountingDownloadTask(failure = cause),
        )

        val error = assertFailsWith<RuntimeException> {
            runner.run(config())
        }

        assertEquals(cause, error)
    }

    @Test
    fun `unchanged attachment reuses blob without redownloading`() = runTest {
        val repository = MemoryBackupRepository()
        val downloadTask = CountingDownloadTask()
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
        )
        val config = config()

        val first = runner.run(config)
        val second = runner.run(config)

        assertEquals(false, first.skipped)
        assertEquals(false, second.skipped)
        assertEquals(1, downloadTask.calls)
        assertEquals(1, repository.blobWrites)
        assertEquals(0, requireNotNull(second.stats).newBlobCount)
        assertEquals(1, second.stats.reusedBlobCount)
    }

    @Test
    fun `attachment key change reuses blob without redownloading`() = runTest {
        val repository = MemoryBackupRepository()
        val downloadTask = CountingDownloadTask()
        val cryptoGenerator = IncrementingCryptoGenerator()
        val config = config()
        val firstRunner = runner(
            repository = repository,
            downloadTask = downloadTask,
            cryptoGenerator = cryptoGenerator,
            exportVaultDataService = StaticExportVaultDataService(
                ciphers = listOf(testCipher(attachmentKeyBase64 = "key-1")),
            ),
        )
        val secondRunner = runner(
            repository = repository,
            downloadTask = downloadTask,
            cryptoGenerator = cryptoGenerator,
            exportVaultDataService = StaticExportVaultDataService(
                ciphers = listOf(testCipher(attachmentKeyBase64 = "key-2")),
            ),
        )

        firstRunner.run(config)
        val firstBlobId = repository.index.attachments.values.single().blobId
        val second = secondRunner.run(config)

        assertEquals(1, downloadTask.calls)
        assertEquals(1, repository.blobWrites)
        assertEquals(firstBlobId, repository.index.attachments.values.single().blobId)
        assertEquals(0, requireNotNull(second.stats).newBlobCount)
        assertEquals(1, second.stats.reusedBlobCount)
    }

    @Test
    fun `legacy key-derived index entry is not reused`() = runTest {
        val repository = MemoryBackupRepository()
        val downloadTask = CountingDownloadTask()
        val cryptoGenerator = IncrementingCryptoGenerator()
        val cipher = testCipher()
        val attachment = cipher.attachments.single() as DSecret.Attachment.Remote
        val legacyFingerprint = legacyAttachmentFingerprint(
            cipher = cipher,
            attachment = attachment,
            cryptoGenerator = cryptoGenerator,
        )
        val currentFingerprint = BackupAttachmentFingerprint.remote(
            cipher = cipher,
            attachment = attachment,
            cryptoGenerator = cryptoGenerator,
        )
        val legacyBlobId = "legacy-blob"
        val legacyCreatedAt = Clock.System.now() - 1.hours
        val legacyBlob = indexBlob(
            blobId = legacyBlobId,
            createdAt = legacyCreatedAt,
        )
        repository.index = BackupIndex(
            indexId = "legacy-index",
            generation = 1L,
            updatedAt = legacyCreatedAt,
            snapshots = mapOf(
                "legacy-snapshot" to indexSnapshot(
                    snapshotId = "legacy-snapshot",
                    blobId = legacyBlobId,
                    createdAt = legacyCreatedAt,
                ),
            ),
            attachments = mapOf(
                legacyFingerprint to BackupIndexAttachment(
                    blobId = legacyBlobId,
                    plainSize = attachment.size,
                    createdAt = legacyCreatedAt,
                    lastSeenAt = legacyCreatedAt,
                ),
            ),
            blobs = mapOf(legacyBlobId to legacyBlob),
        )
        repository.blobs += legacyBlob.path
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
            cryptoGenerator = cryptoGenerator,
            exportVaultDataService = StaticExportVaultDataService(ciphers = listOf(cipher)),
        )

        val result = runner.run(config())

        assertNotEquals(legacyFingerprint, currentFingerprint)
        assertEquals(1, downloadTask.calls)
        assertEquals(1, repository.blobWrites)
        assertEquals(1, requireNotNull(result.stats).newBlobCount)
        assertEquals(0, result.stats.reusedBlobCount)
        assertTrue(legacyFingerprint in repository.index.attachments)
        assertTrue(currentFingerprint in repository.index.attachments)
    }

    @Test
    fun `backup runs without archive password`() = runTest {
        val repository = MemoryBackupRepository()
        val downloadTask = CountingDownloadTask()
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
        )

        val result = runner.run(
            config().copy(
                password = null,
            ),
        )

        assertEquals(false, result.skipped)
        assertEquals(1, downloadTask.calls)
        assertEquals(1, repository.blobWrites)
        assertEquals(1, repository.snapshots.size)
        assertTrue(repository.index.attachments.isNotEmpty())
        assertTrue(repository.index.blobs.values.all {
            it.encryption.method == BackupObjectEncryptionMethod.None
        })
        assertTrue(repository.index.snapshots.values.all {
            it.encryption.method == BackupObjectEncryptionMethod.None
        })
    }

    @Test
    fun `missing blob with existing index entry refetches attachment`() = runTest {
        val repository = MemoryBackupRepository()
        val downloadTask = CountingDownloadTask()
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
        )
        val config = config()

        runner.run(config)
        repository.blobs.clear()
        val second = runner.run(config)

        assertEquals(2, downloadTask.calls)
        assertEquals(2, repository.blobWrites)
        assertEquals(1, requireNotNull(second.stats).newBlobCount)
        assertEquals(0, second.stats.reusedBlobCount)
    }

    @Test
    fun `cached blob younger than validation window is reused without validation`() = runTest {
        val repository = MemoryBackupRepository()
        val downloadTask = CountingDownloadTask()
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
        )
        val config = config()

        runner.run(config)
        repository.blobValidationCalls.clear()
        val second = runner.run(config)

        assertEquals(1, downloadTask.calls)
        assertEquals(0, repository.blobValidationCalls.size)
        assertEquals(1, requireNotNull(second.stats).reusedBlobCount)
    }

    @Test
    fun `cached blob between one and two weeks validates only when probability matches`() = runTest {
        val repository = MemoryBackupRepository()
        val downloadTask = CountingDownloadTask()
        val cryptoGenerator = IncrementingCryptoGenerator(randomRoll = 30)
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
            cryptoGenerator = cryptoGenerator,
        )
        val config = config()

        runner.run(config)
        val oldValidation = Clock.System.now() - 8.days
        replaceSingleBlob(repository) { blob ->
            blob.copy(lastValidatedAt = oldValidation)
        }
        repository.blobValidationCalls.clear()
        runner.run(config)

        assertEquals(0, repository.blobValidationCalls.size)

        val validatingRepository = MemoryBackupRepository()
        val validatingDownloadTask = CountingDownloadTask()
        val validatingRunner = runner(
            repository = validatingRepository,
            downloadTask = validatingDownloadTask,
            cryptoGenerator = IncrementingCryptoGenerator(randomRoll = 29),
        )
        validatingRunner.run(config)
        val validatingBlob = replaceSingleBlob(validatingRepository) { blob ->
            blob.copy(lastValidatedAt = oldValidation)
        }
        validatingRepository.blobValidationCalls.clear()
        validatingRunner.run(config)

        assertEquals(listOf(validatingBlob.path), validatingRepository.blobValidationCalls)
        assertEquals(1, validatingDownloadTask.calls)
    }

    @Test
    fun `cached blob older than two weeks validates regardless of probability`() = runTest {
        val repository = MemoryBackupRepository()
        val downloadTask = CountingDownloadTask()
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
            cryptoGenerator = IncrementingCryptoGenerator(randomRoll = 99),
        )
        val config = config()

        runner.run(config)
        val oldValidation = Clock.System.now() - 15.days
        val blob = replaceSingleBlob(repository) { it.copy(lastValidatedAt = oldValidation) }
        repository.blobValidationCalls.clear()
        runner.run(config)

        assertEquals(listOf(blob.path), repository.blobValidationCalls)
        assertEquals(1, downloadTask.calls)
    }

    @Test
    fun `successful blob validation refreshes timestamp without redownloading`() = runTest {
        val repository = MemoryBackupRepository()
        val downloadTask = CountingDownloadTask()
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
        )
        val config = config()

        runner.run(config)
        val oldValidation = Clock.System.now() - 15.days
        val blob = replaceSingleBlob(repository) { it.copy(lastValidatedAt = oldValidation) }
        repository.blobValidationCalls.clear()
        runner.run(config)

        val refreshed = repository.index.blobs.getValue(repository.index.attachments.values.single().blobId)
        assertEquals(listOf(blob.path), repository.blobValidationCalls)
        assertEquals(1, downloadTask.calls)
        assertTrue(requireNotNull(refreshed.lastValidatedAt) > oldValidation)
    }

    @Test
    fun `invalid blob validation redownloads attachment and indexes new blob`() = runTest {
        val repository = MemoryBackupRepository()
        val downloadTask = CountingDownloadTask()
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
        )
        val config = config()

        runner.run(config)
        val oldValidation = Clock.System.now() - 15.days
        val oldBlob = replaceSingleBlob(repository) { it.copy(lastValidatedAt = oldValidation) }
        repository.blobValidationResults[oldBlob.path] = BackupBlobValidationResult.Invalid
        repository.blobValidationCalls.clear()
        val second = runner.run(config)

        val newBlobId = repository.index.attachments.values.single().blobId
        val newBlob = repository.index.blobs.getValue(newBlobId)
        assertEquals(listOf(oldBlob.path), repository.blobValidationCalls)
        assertEquals(2, downloadTask.calls)
        assertEquals(2, repository.blobWrites)
        assertEquals(1, requireNotNull(second.stats).newBlobCount)
        assertEquals(0, second.stats.reusedBlobCount)
        assertTrue(newBlob.path != oldBlob.path)
        assertTrue(requireNotNull(newBlob.lastValidatedAt) > oldValidation)
    }

    @Test
    fun `unavailable blob validation reuses cached blob and preserves timestamp`() = runTest {
        val repository = MemoryBackupRepository()
        val downloadTask = CountingDownloadTask()
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
        )
        val config = config()

        runner.run(config)
        val oldValidation = Clock.System.now() - 15.days
        val oldBlob = replaceSingleBlob(repository) { it.copy(lastValidatedAt = oldValidation) }
        repository.blobValidationResults[oldBlob.path] = BackupBlobValidationResult.Unavailable
        repository.blobValidationCalls.clear()
        val second = runner.run(config)

        val reusedBlob = repository.index.blobs.getValue(repository.index.attachments.values.single().blobId)
        assertEquals(listOf(oldBlob.path), repository.blobValidationCalls)
        assertEquals(1, downloadTask.calls)
        assertEquals(0, requireNotNull(second.stats).newBlobCount)
        assertEquals(1, second.stats.reusedBlobCount)
        assertEquals(oldBlob.path, reusedBlob.path)
        assertEquals(oldValidation, reusedBlob.lastValidatedAt)
    }

    @Test
    fun `missing validation timestamp falls back to blob creation time`() = runTest {
        val repository = MemoryBackupRepository()
        val downloadTask = CountingDownloadTask()
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
        )
        val config = config()

        runner.run(config)
        val oldCreatedAt = Clock.System.now() - 15.days
        val blob = replaceSingleBlob(repository) {
            it.copy(
                createdAt = oldCreatedAt,
                lastValidatedAt = null,
            )
        }
        repository.blobValidationCalls.clear()
        runner.run(config)

        assertEquals(listOf(blob.path), repository.blobValidationCalls)
        assertEquals(1, downloadTask.calls)
    }

    @Test
    fun `validation decision is reused for duplicate indexed blob ids`() = runTest {
        val repository = MemoryBackupRepository()
        val downloadTask = CountingDownloadTask()
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
            exportVaultDataService = StaticExportVaultDataService(
                ciphers = listOf(
                    testCipher(),
                    testCipher(
                        id = "local-cipher-2",
                        remoteCipherId = "remote-cipher-2",
                        attachmentId = "attachment-2",
                        attachmentKeyBase64 = "key-2",
                    ),
                ),
            ),
        )
        val config = config()

        runner.run(config)
        val index = repository.index
        val sharedBlobEntry = index.blobs.entries.first()
        val oldValidation = Clock.System.now() - 15.days
        val sharedBlob = sharedBlobEntry.value.copy(lastValidatedAt = oldValidation)
        repository.index = index.copy(
            attachments = index.attachments.mapValues { (_, attachment) ->
                attachment.copy(blobId = sharedBlobEntry.key)
            },
            blobs = index.blobs + (sharedBlobEntry.key to sharedBlob),
        )
        repository.blobValidationCalls.clear()
        val second = runner.run(config)

        assertEquals(listOf(sharedBlob.path), repository.blobValidationCalls)
        assertEquals(2, downloadTask.calls)
        assertEquals(2, requireNotNull(second.stats).reusedBlobCount)
    }

    @Test
    fun `snapshot write failure leaves index unchanged`() = runTest {
        val repository = MemoryBackupRepository().apply {
            failWriteSnapshot = RuntimeException("snapshot failed")
        }
        val downloadTask = CountingDownloadTask()
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
        )

        assertFailsWith<RuntimeException> {
            runner.run(config())
        }

        assertEquals(BackupIndex(), repository.index)
        assertTrue(repository.snapshots.isEmpty())
        assertTrue("writeIndex" !in repository.operations)
    }

    @Test
    fun `index read failure fails run without writing snapshot or index`() = runTest {
        val repository = MemoryBackupRepository().apply {
            failReadIndexes = BackupObjectStoreException.Transient(
                operation = BackupObjectStoreOperation.List,
            )
        }
        val downloadTask = CountingDownloadTask()
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
        )

        val error = assertFailsWith<BackupObjectStoreException.Transient> {
            runner.run(config())
        }

        assertEquals(BackupObjectStoreOperation.List, error.operation)
        assertEquals(0, downloadTask.calls)
        assertTrue(repository.snapshots.isEmpty())
        assertTrue("writeSnapshot" !in repository.operations)
        assertTrue("writeIndex" !in repository.operations)
    }

    @Test
    fun `writes snapshot before index`() = runTest {
        val repository = MemoryBackupRepository()
        val downloadTask = CountingDownloadTask()
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
        )

        runner.run(config())

        val snapshotWrite = repository.operations.indexOf("writeSnapshot")
        val indexWrite = repository.operations.indexOf("writeIndex")
        assertTrue(snapshotWrite >= 0)
        assertTrue(indexWrite > snapshotWrite)
    }

    @Test
    fun `missing index starts empty and redownloads attachment`() = runTest {
        val repository = MemoryBackupRepository()
        val downloadTask = CountingDownloadTask()
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
        )
        val config = config()

        runner.run(config)
        repository.index = BackupIndex()
        val second = runner.run(config)

        assertEquals(2, downloadTask.calls)
        assertEquals(1, requireNotNull(second.stats).newBlobCount)
        assertEquals(0, second.stats.reusedBlobCount)
        assertTrue(repository.index.attachments.isNotEmpty())
        assertTrue(repository.index.blobs.isNotEmpty())
    }

    @Test
    fun `retention writes retained index before deleting garbage`() = runTest {
        val repository = MemoryBackupRepository()
        val downloadTask = CountingDownloadTask()
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
        )
        val config = config().copy(
            retention = BackupRetention(
                maxSnapshots = 1,
            ),
        )

        runner.run(config)
        runner.run(config)

        val lastRetentionIndexWrite = repository.operations.indexOfLast { it == "writeIndex" }
        val firstDeleteSnapshot = repository.operations.indexOf("deleteSnapshot")

        assertTrue(lastRetentionIndexWrite >= 0)
        assertTrue(firstDeleteSnapshot > lastRetentionIndexWrite)
        assertEquals(1, repository.index.snapshots.size)
    }

    @Test
    fun `retention prunes snapshots older than one month`() = runTest {
        val repository = MemoryBackupRepository()
        val downloadTask = CountingDownloadTask()
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
        )
        val now = Clock.System.now()
        seedRetentionSnapshots(
            repository = repository,
            updatedAt = now,
            createdAtBySnapshotId = mapOf(
                "snapshot-inside-month" to now - 30.days,
                "snapshot-older-than-month" to now - 32.days,
            ),
        )

        runner.run(
            config().copy(
                includeAttachments = false,
                retention = BackupRetention(
                    maxSnapshots = 30,
                ),
            ),
        )

        assertTrue("snapshot-inside-month" in repository.index.snapshots)
        assertTrue("snapshot-older-than-month" !in repository.index.snapshots)
        assertEquals(listOf("snapshot-older-than-month"), repository.deletedSnapshots)
        assertTrue(retentionBlobPath("snapshot-inside-month") in repository.blobs)
        assertTrue(retentionBlobPath("snapshot-older-than-month") !in repository.blobs)
    }

    @Test
    fun `retention never clear keeps all snapshots and blobs`() = runTest {
        val repository = MemoryBackupRepository()
        val downloadTask = CountingDownloadTask()
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
        )
        val now = Clock.System.now()
        seedRetentionSnapshots(
            repository = repository,
            updatedAt = now,
            createdAtBySnapshotId = mapOf(
                "snapshot-inside-month" to now - 30.days,
                "snapshot-older-than-month" to now - 32.days,
            ),
        )
        val seededSnapshotIds = repository.index.snapshots.keys.toSet()
        val seededBlobPaths = repository.blobs.toSet()

        runner.run(
            config().copy(
                includeAttachments = false,
                retention = BackupRetention(
                    maxSnapshots = BackupRetention.NEVER_CLEAR_MAX_SNAPSHOTS,
                ),
            ),
        )

        assertTrue(seededSnapshotIds.all { it in repository.index.snapshots })
        assertTrue(seededBlobPaths.all { it in repository.blobs })
        assertTrue(repository.deletedSnapshots.isEmpty())
        assertEquals(1, repository.operations.count { it == "writeIndex" })
        assertTrue("deleteSnapshot" !in repository.operations)
        assertTrue("deleteBlob" !in repository.operations)
    }

    @Test
    fun `retention keeps weekly representatives before recent fill`() = runTest {
        val repository = MemoryBackupRepository()
        val downloadTask = CountingDownloadTask()
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
        )
        val now = Clock.System.now()
        seedRetentionSnapshots(
            repository = repository,
            updatedAt = now,
            createdAtBySnapshotId = mapOf(
                "snapshot-1-hour" to now - 1.hours,
                "snapshot-2-hours" to now - 2.hours,
                "snapshot-1-day" to now - 1.days,
                "snapshot-2-days" to now - 2.days,
                "snapshot-3-days" to now - 3.days,
                "snapshot-4-days" to now - 4.days,
                "snapshot-5-days" to now - 5.days,
                "snapshot-8-days" to now - 8.days,
                "snapshot-15-days" to now - 15.days,
                "snapshot-22-days" to now - 22.days,
                "snapshot-29-days" to now - 29.days,
            ),
        )

        runner.run(
            config().copy(
                includeAttachments = false,
                retention = BackupRetention(
                    maxSnapshots = 5,
                ),
            ),
        )

        val retainedSnapshotIds = repository.index.snapshots.keys
        val generatedSnapshotIds = retainedSnapshotIds - setOf(
            "snapshot-1-day",
            "snapshot-2-days",
            "snapshot-3-days",
            "snapshot-4-days",
            "snapshot-5-days",
            "snapshot-8-days",
            "snapshot-15-days",
            "snapshot-22-days",
            "snapshot-29-days",
        )
        assertEquals(1, generatedSnapshotIds.size)
        assertEquals(
            setOf(
                generatedSnapshotIds.single(),
                "snapshot-8-days",
                "snapshot-15-days",
                "snapshot-22-days",
                "snapshot-29-days",
            ),
            retainedSnapshotIds,
        )
    }

    @Test
    fun `retention fills remaining budget with newest monthly snapshots`() = runTest {
        val repository = MemoryBackupRepository()
        val downloadTask = CountingDownloadTask()
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
        )
        val now = Clock.System.now()
        seedRetentionSnapshots(
            repository = repository,
            updatedAt = now,
            createdAtBySnapshotId = mapOf(
                "snapshot-1-day" to now - 1.days,
                "snapshot-2-days" to now - 2.days,
                "snapshot-3-days" to now - 3.days,
                "snapshot-8-days" to now - 8.days,
                "snapshot-15-days" to now - 15.days,
                "snapshot-22-days" to now - 22.days,
                "snapshot-29-days" to now - 29.days,
            ),
        )

        runner.run(
            config().copy(
                includeAttachments = false,
                retention = BackupRetention(
                    maxSnapshots = 7,
                ),
            ),
        )

        val retainedSnapshotIds = repository.index.snapshots.keys
        assertEquals(7, retainedSnapshotIds.size)
        assertTrue("snapshot-1-hour" !in retainedSnapshotIds)
        assertTrue("snapshot-2-hours" !in retainedSnapshotIds)
        assertTrue("snapshot-1-day" in retainedSnapshotIds)
        assertTrue("snapshot-2-days" in retainedSnapshotIds)
        assertTrue("snapshot-3-days" !in retainedSnapshotIds)
        assertTrue("snapshot-8-days" in retainedSnapshotIds)
        assertTrue("snapshot-15-days" in retainedSnapshotIds)
        assertTrue("snapshot-22-days" in retainedSnapshotIds)
        assertTrue("snapshot-29-days" in retainedSnapshotIds)
    }

    @Test
    fun `same generation index heads are merged into next backup index`() = runTest {
        val repository = MemoryBackupRepository()
        val downloadTask = CountingDownloadTask()
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
        )
        val now = Clock.System.now()
        repository.indexes += BackupIndex(
            indexId = "head-a",
            generation = 1L,
            updatedAt = now - 2.days,
            snapshots = mapOf(
                "snapshot-a" to indexSnapshot(
                    snapshotId = "snapshot-a",
                    blobId = "blob-a",
                    createdAt = now - 2.days,
                ),
            ),
            blobs = mapOf(
                "blob-a" to indexBlob(
                    blobId = "blob-a",
                    createdAt = now - 2.days,
                ),
            ),
        )
        repository.indexes += BackupIndex(
            indexId = "head-b",
            generation = 1L,
            updatedAt = now - 1.days,
            snapshots = mapOf(
                "snapshot-b" to indexSnapshot(
                    snapshotId = "snapshot-b",
                    blobId = "blob-b",
                    createdAt = now - 1.days,
                ),
            ),
            blobs = mapOf(
                "blob-b" to indexBlob(
                    blobId = "blob-b",
                    createdAt = now - 1.days,
                ),
            ),
        )

        runner.run(config())

        assertEquals(setOf("head-a", "head-b"), repository.index.parentIndexIds)
        assertTrue("snapshot-a" in repository.index.snapshots)
        assertTrue("snapshot-b" in repository.index.snapshots)
    }

    @Test
    fun `diagnostics records first backup without sensitive fields`() = runTest {
        val repository = MemoryBackupRepository()
        val downloadTask = CountingDownloadTask()
        val logRepository = TestBackupLogRepository()
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
            diagnostics = BackupDiagnostics(
                logRepository = logRepository,
                enabled = true,
            ),
        )

        runner.run(config())

        val messages = logRepository.messages
        assertTrue(messages.any { it.contains("backup_run_started include_attachments=true") })
        assertTrue(messages.any { it.contains("backup_repository_ready") })
        assertTrue(messages.any { it.contains("backup_index_loaded generation=0") })
        assertTrue(messages.any { it.contains("backup_export_created cipher_count=1") })
        assertTrue(
            messages.any {
                it.contains("backup_attachment_download_started") &&
                        it.contains("local_cipher_id=local-cipher-1") &&
                        it.contains("attachment_id=attachment-1") &&
                        it.contains("source_type=direct")
            },
        )
        assertTrue(messages.any { it.contains("backup_snapshot_written snapshot_id=") })
        assertTrue(messages.any { it.contains("backup_retention_completed") })
        assertTrue(messages.any { it.contains("backup_run_completed") })
        assertTrue(messages.none { it.contains("/tmp/keyguard-backups") })
        assertTrue(messages.none { it.contains("password") })
        assertTrue(messages.none { it.contains("file.txt") })
        assertTrue(messages.none { it.contains("https://example.com/file") })
        assertTrue(messages.none { it.contains("payload") })
    }

    @Test
    fun `diagnostics records reused attachment blob`() = runTest {
        val repository = MemoryBackupRepository()
        val downloadTask = CountingDownloadTask()
        val logRepository = TestBackupLogRepository()
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
            diagnostics = BackupDiagnostics(
                logRepository = logRepository,
                enabled = true,
            ),
        )
        val config = config()

        runner.run(config)
        logRepository.entries.clear()
        runner.run(config)

        val messages = logRepository.messages
        assertTrue(
            messages.any {
                it.contains("backup_attachment_blob_reused") &&
                        it.contains("local_cipher_id=local-cipher-1") &&
                        it.contains("attachment_id=attachment-1")
            },
        )
        assertTrue(messages.any { it.contains("backup_attachments_completed attachment_count=1") })
        assertTrue(messages.any { it.contains("reused_blob_count=1") })
        assertTrue(messages.none { it.contains("backup_attachment_download_started") })
    }

    @Test
    fun `progress reporter records ordered steps and attachment bytes`() = runTest {
        val repository = MemoryBackupRepository()
        val downloadTask = CountingDownloadTask(
            loadingEvents = listOf(
                DownloadProgress.Loading(
                    downloaded = 3L,
                    total = 7L,
                ),
                DownloadProgress.Loading(
                    downloaded = 7L,
                    total = 7L,
                ),
            ),
        )
        val runner = runner(
            repository = repository,
            downloadTask = downloadTask,
        )
        val events = mutableListOf<BackupRunProgress>()

        runner.run(
            config = config(),
            progress = BackupRunProgressContext(
                runId = "run-1",
                trigger = "manual",
                startedAt = Instant.fromEpochMilliseconds(10L),
                reporter = BackupProgressReporter { progress ->
                    events += progress
                },
            ),
        )

        assertEquals(
            listOf(
                BackupStep.Preparing,
                BackupStep.OpeningRepository,
                BackupStep.ExportingVault,
                BackupStep.ScanningAttachments,
                BackupStep.BackingUpAttachments,
                BackupStep.WritingSnapshot,
                BackupStep.WritingIndex,
                BackupStep.ApplyingRetention,
            ),
            events.map { it.step }.distinct(),
        )
        assertTrue(
            events.any { event ->
                event.step == BackupStep.BackingUpAttachments &&
                        event.details.downloadedBytes == 3L &&
                        event.details.totalBytes == 7L
            },
        )
        assertTrue(
            events.any { event ->
                event.step == BackupStep.BackingUpAttachments &&
                        event.details.itemsProcessed == 1 &&
                        event.details.itemsTotal == 1 &&
                        event.details.downloadedBytes == 7L &&
                        event.details.totalBytes == 7L
            },
        )
    }

    private fun runner(
        repository: BackupRepository,
        downloadTask: CountingDownloadTask,
        cryptoGenerator: CryptoGenerator = IncrementingCryptoGenerator(),
        diagnostics: BackupDiagnostics = BackupDiagnostics.NoOp,
        exportVaultDataService: ExportVaultDataService = FakeExportVaultDataService,
    ) = BackupRunner(
        exportVaultDataService = exportVaultDataService,
        backupRepository = repository,
        backupObjectStoreFactory = FakeBackupObjectStoreFactory,
        cryptoGenerator = cryptoGenerator,
        base64Service = Base64ServiceImpl(),
        dateFormatter = FixedDateFormatter,
        downloadTask = downloadTask,
        downloadAttachmentMetadata = FakeDownloadAttachmentMetadata,
        diagnostics = diagnostics,
    )

    private fun config() = BackupConfig(
        enabled = true,
        store = BackupStoreConfig.Local(
            path = "/tmp/keyguard-backups",
        ),
        password = Password("password"),
        includeAttachments = true,
    )

    private fun replaceSingleBlob(
        repository: MemoryBackupRepository,
        transform: (BackupIndexBlob) -> BackupIndexBlob,
    ): BackupIndexBlob {
        val index = repository.index
        val entry = index.blobs.entries.single()
        val updatedBlob = transform(entry.value)
        repository.index = index.copy(
            blobs = index.blobs + (entry.key to updatedBlob),
        )
        return updatedBlob
    }

    private fun seedRetentionSnapshots(
        repository: MemoryBackupRepository,
        updatedAt: Instant,
        createdAtBySnapshotId: Map<String, Instant>,
    ) {
        val snapshots = createdAtBySnapshotId
            .mapValues { (snapshotId, createdAt) ->
                indexSnapshot(
                    snapshotId = snapshotId,
                    blobId = retentionBlobId(snapshotId),
                    createdAt = createdAt,
                )
            }
        val blobs = createdAtBySnapshotId
            .map { (snapshotId, createdAt) ->
                val blobId = retentionBlobId(snapshotId)
                blobId to indexBlob(
                    blobId = blobId,
                    createdAt = createdAt,
                )
            }
            .toMap()
        repository.indexes += BackupIndex(
            indexId = "seed-index",
            generation = 1L,
            updatedAt = updatedAt,
            snapshots = snapshots,
            blobs = blobs,
        )
        repository.blobs += blobs.values.map { it.path }
    }

    private fun retentionBlobPath(
        snapshotId: String,
    ): String = BackupAttachmentFingerprint.blobPath(retentionBlobId(snapshotId))

    private fun retentionBlobId(
        snapshotId: String,
    ): String = "blob-$snapshotId"
}

private object FakeBackupObjectStoreFactory : BackupObjectStoreFactory {
    override suspend fun open(
        store: BackupStoreConfig,
    ): BackupObjectStore = FakeBackupObjectStore
}

private object FakeBackupObjectStore : BackupObjectStore {
    override val capabilities: BackupObjectStoreCapabilities = BackupObjectStoreCapabilities(
        atomicWholeObjectWrite = true,
        atomicReplace = true,
        rangeRead = true,
        strongReadAfterWrite = true,
        strongListAfterWrite = true,
    )

    override suspend fun stat(
        key: BackupObjectKey,
    ): BackupObjectInfo? = error("MemoryBackupRepository does not use the object store.")

    override suspend fun read(
        key: BackupObjectKey,
        range: BackupByteRange?,
    ): kotlinx.io.Source = error("MemoryBackupRepository does not use the object store.")

    override suspend fun write(
        key: BackupObjectKey,
        mode: BackupWriteMode,
        write: suspend (Sink) -> Unit,
    ): BackupObjectInfo = error("MemoryBackupRepository does not use the object store.")

    override suspend fun list(
        prefix: BackupObjectKeyPrefix,
        cursor: BackupListCursor?,
    ): BackupObjectListPage = error("MemoryBackupRepository does not use the object store.")

    override suspend fun delete(
        key: BackupObjectKey,
    ) {
        error("MemoryBackupRepository does not use the object store.")
    }
}

private object FakeExportVaultDataService : ExportVaultDataService {
    override suspend fun create(
        filter: DFilter,
    ): ExportVaultData = ExportVaultData(
        ciphers = listOf(testCipher()),
        folders = emptyList(),
        collections = emptyList(),
        organizations = emptyList(),
    )

    override fun exportJson(
        data: ExportVaultData,
    ): String = "{}"
}

private class StaticExportVaultDataService(
    private val ciphers: List<DSecret>,
) : ExportVaultDataService {
    override suspend fun create(
        filter: DFilter,
    ): ExportVaultData = ExportVaultData(
        ciphers = ciphers,
        folders = emptyList(),
        collections = emptyList(),
        organizations = emptyList(),
    )

    override fun exportJson(
        data: ExportVaultData,
    ): String = "{}"
}

private object FakeDownloadAttachmentMetadata : DownloadAttachmentMetadata {
    override fun invoke(
        request: DownloadAttachmentRequest,
    ): IO<DownloadAttachmentRequestData> = {
        DownloadAttachmentRequestData(
            localCipherId = "local-cipher-1",
            remoteCipherId = "remote-cipher-1",
            attachmentId = "attachment-1",
            source = DownloadAttachmentRequestData.DirectSource(
                data = "payload".encodeToByteArray(),
            ),
            name = "file.txt",
            encryptionKey = null,
        )
    }
}

private class CountingDownloadTask(
    private val loadingEvents: List<DownloadProgress.Loading> = emptyList(),
    private val failure: Throwable? = null,
) : DownloadTask {
    var calls: Int = 0

    override fun fileLoader(
        data: ByteArray,
        key: ByteArray?,
        writer: DownloadWriter,
    ) = flow {
        calls += 1
        loadingEvents.forEach { event ->
            emit(event)
        }
        failure?.let {
            emit(DownloadProgress.Complete(it.left()))
            return@flow
        }
        writer.writeBytes(data)
        emit(DownloadProgress.Complete(null.right()))
    }

    override fun fileLoader(
        url: String,
        key: ByteArray?,
        writer: DownloadWriter,
    ) = flow {
        calls += 1
        loadingEvents.forEach { event ->
            emit(event)
        }
        failure?.let {
            emit(DownloadProgress.Complete(it.left()))
            return@flow
        }
        writer.writeBytes("payload".encodeToByteArray())
        emit(DownloadProgress.Complete(null.right()))
    }
}

private fun indexSnapshot(
    snapshotId: String,
    blobId: String,
    createdAt: Instant,
) = BackupIndexSnapshot(
    path = "snapshots/$snapshotId.zip",
    createdAt = createdAt,
    vaultSize = 2L,
    blobIds = setOf(blobId),
    stats = BackupSnapshotStats(
        cipherCount = 0,
        attachmentCount = 0,
        newBlobCount = 0,
        reusedBlobCount = 0,
    ),
)

private fun indexBlob(
    blobId: String,
    createdAt: Instant,
    lastValidatedAt: Instant? = null,
) = BackupIndexBlob(
    path = BackupAttachmentFingerprint.blobPath(blobId),
    plainSize = 1L,
    encryptedSize = 2L,
    createdAt = createdAt,
    lastSeenAt = createdAt,
    lastValidatedAt = lastValidatedAt,
)

private class MemoryBackupRepository : BackupRepository {
    val blobs = mutableSetOf<String>()
    val snapshots = mutableMapOf<String, BackupSnapshotManifest>()
    val indexes = mutableListOf<BackupIndex>()
    val operations = mutableListOf<String>()
    val deletedSnapshots = mutableListOf<String>()
    val unreadableSnapshotIds = mutableSetOf<String>()
    var index: BackupIndex
        get() = indexes.lastOrNull() ?: BackupIndex()
        set(value) {
            indexes.clear()
            if (value.indexId.isNotBlank()) {
                indexes += value
            }
        }
    var blobWrites: Int = 0
    var failReadIndexes: Throwable? = null
    var failWriteSnapshot: Throwable? = null
    var makeNextSnapshotUnreadable: Boolean = false
    val blobValidationCalls = mutableListOf<String>()
    val blobValidationResults = mutableMapOf<String, BackupBlobValidationResult>()

    override suspend fun getOrCreateMetadata(
        store: BackupObjectStore,
        password: Password?,
        nowProvider: () -> Instant,
        repoIdProvider: () -> String,
    ): BackupRepositoryMetadata {
        val now = nowProvider()
        val repoId = repoIdProvider()
        return BackupRepositoryMetadata(
            repoId = repoId,
            createdAt = now,
        )
    }

    override suspend fun readIndexes(
        store: BackupObjectStore,
        password: Password?,
    ): List<BackupIndex> {
        failReadIndexes?.let { throw it }
        val latestGeneration = indexes
            .maxOfOrNull { it.generation }
            ?: return emptyList()
        return indexes
            .filter { it.generation == latestGeneration }
            .sortedByDescending { it.indexId }
    }

    override suspend fun writeIndex(
        store: BackupObjectStore,
        password: Password?,
        index: BackupIndex,
    ) {
        operations += "writeIndex"
        indexes += index
    }

    override suspend fun hasBlob(
        store: BackupObjectStore,
        blobPath: String,
    ): Boolean = blobPath in blobs

    override suspend fun validateBlob(
        store: BackupObjectStore,
        objectPassword: Password?,
        blobPath: String,
    ): BackupBlobValidationResult {
        operations += "validateBlob"
        blobValidationCalls += blobPath
        return blobValidationResults[blobPath]
            ?: if (blobPath in blobs) {
                BackupBlobValidationResult.Valid
            } else {
                BackupBlobValidationResult.Invalid
            }
    }

    override suspend fun writeBlob(
        store: BackupObjectStore,
        objectPassword: Password?,
        blobPath: String,
        write: suspend (Sink) -> Unit,
    ): Long {
        operations += "writeBlob"
        blobWrites += 1
        val buffer = Buffer()
        write(buffer)
        val bytes = buffer.readByteArray()
        blobs += blobPath
        return bytes.size.toLong()
    }

    override suspend fun writeSnapshot(
        store: BackupObjectStore,
        objectPassword: Password?,
        snapshotId: String,
        manifest: BackupSnapshotManifest,
        vaultJson: String,
    ) {
        operations += "writeSnapshot"
        failWriteSnapshot?.let { throw it }
        snapshots[snapshotId] = manifest
        if (makeNextSnapshotUnreadable) {
            unreadableSnapshotIds += snapshotId
            makeNextSnapshotUnreadable = false
        }
    }

    override suspend fun listSnapshotIds(
        store: BackupObjectStore,
    ): List<String> = snapshots.keys.toList()

    override suspend fun readSnapshotManifest(
        store: BackupObjectStore,
        objectPassword: Password?,
        snapshotId: String,
    ): BackupSnapshotManifest? = snapshots[snapshotId]
        ?.takeIf { snapshotId !in unreadableSnapshotIds }

    override suspend fun deleteSnapshot(
        store: BackupObjectStore,
        snapshotId: String,
    ) {
        operations += "deleteSnapshot"
        deletedSnapshots += snapshotId
        snapshots.remove(snapshotId)
    }

    override suspend fun deleteBlob(
        store: BackupObjectStore,
        blobPath: String,
    ) {
        operations += "deleteBlob"
        blobs.remove(blobPath)
    }
}

private object FixedDateFormatter : DateFormatter {
    override fun formatDateTimeMachine(
        instant: Instant,
    ): String = "20260101T000000"

    override fun formatDateTime(
        instant: Instant,
    ): String = formatDateTimeMachine(instant)

    override fun formatDate(
        instant: Instant,
    ): String = formatDateTimeMachine(instant)

    override suspend fun formatDateShort(
        instant: Instant,
    ): String = formatDateTimeMachine(instant)

    override suspend fun formatDateShort(
        date: LocalDate,
    ): String = date.toString()

    override fun formatDateMedium(
        date: LocalDate,
    ): String = date.toString()

    override fun formatTimeShort(
        time: LocalTime,
    ): String = time.toString()
}

private fun testCipher(
    id: String = "local-cipher-1",
    remoteCipherId: String = "remote-cipher-1",
    attachmentId: String = "attachment-1",
    attachmentKeyBase64: String = "key-1",
) = DSecret(
    id = id,
    accountId = "account-1",
    folderId = null,
    organizationId = null,
    collectionIds = emptySet(),
    revisionDate = Instant.fromEpochMilliseconds(1L),
    createdDate = null,
    archivedDate = null,
    deletedDate = null,
    service = BitwardenService(
        remote = BitwardenService.Remote(
            id = remoteCipherId,
            revisionDate = Instant.fromEpochMilliseconds(1L),
            deletedDate = null,
        ),
    ),
    name = "Cipher",
    notes = "",
    favorite = false,
    reprompt = false,
    synced = true,
    attachments = listOf(
        DSecret.Attachment.Remote(
            id = attachmentId,
            url = "https://example.com/file",
            remoteCipherId = remoteCipherId,
            fileName = "file.txt",
            keyBase64 = attachmentKeyBase64,
            size = 7L,
        ),
    ),
    type = DSecret.Type.None,
)

private fun legacyAttachmentFingerprint(
    cipher: DSecret,
    attachment: DSecret.Attachment.Remote,
    cryptoGenerator: CryptoGenerator,
): String {
    val attachmentKeyFingerprint = attachment.keyBase64
        ?.encodeToByteArray()
        ?.let(cryptoGenerator::hashSha256)
        ?.toHex()
    val payload = buildString {
        append("v1\n")
        appendLegacyFingerprintField("accountId", cipher.accountId)
        appendLegacyFingerprintField("localCipherId", cipher.id)
        appendLegacyFingerprintField("remoteCipherId", attachment.remoteCipherId)
        appendLegacyFingerprintField("attachmentId", attachment.id)
        appendLegacyFingerprintField("size", attachment.size.toString())
        appendLegacyFingerprintField("attachmentKeyFingerprint", attachmentKeyFingerprint)
    }
    return cryptoGenerator
        .hmacSha256(
            key = "keyguard-backup-attachment-fingerprint-v1".encodeToByteArray(),
            data = payload.encodeToByteArray(),
        )
        .toHex()
}

private fun StringBuilder.appendLegacyFingerprintField(
    name: String,
    value: String?,
) {
    append(name)
    append(':')
    append(value?.length ?: -1)
    append(':')
    if (value != null) {
        append(value)
    }
    append('\n')
}

private class IncrementingCryptoGenerator(
    private val randomRoll: Int = 0,
) : CryptoGenerator {
    private var uuidIndex = 0

    override fun hkdf(
        seed: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray = seed

    override fun pbkdf2(
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        length: Int,
    ): ByteArray = seed

    override fun argon2(
        mode: Argon2Mode,
        seed: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
    ): ByteArray = seed

    override fun seed(length: Int): ByteArray = ByteArray(length)

    override fun hmac(
        key: ByteArray,
        data: ByteArray,
        algorithm: CryptoHashAlgorithm,
    ): ByteArray = data

    override fun hashSha1(data: ByteArray): ByteArray = data

    override fun hashSha256(data: ByteArray): ByteArray = data

    override fun hashMd5(data: ByteArray): ByteArray = data

    override fun uuid(): String {
        uuidIndex += 1
        return "uuid-$uuidIndex"
    }

    override fun random(): Int = 0

    override fun random(range: IntRange): Int = randomRoll.coerceIn(range)
}
