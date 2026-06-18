package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStoreStore
import com.artemchep.keyguard.common.io.ioEffect
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class BackupConfigRepositoryTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `set status clears in-memory current run`() = runTest {
        val repository = BackupConfigRepositoryImpl(
            store = JsonKeyValueStore(),
            json = json,
        )
        val startedAt = Instant.fromEpochMilliseconds(1L)
        val progress = BackupRunProgress(
            runId = "run-1",
            trigger = "manual",
            startedAt = startedAt,
            step = BackupStep.BackingUpAttachments,
            details = BackupRunProgressDetails(
                itemsProcessed = 1,
                itemsTotal = 2,
            ),
        )

        assertEquals(null, repository.getStatus().first().currentRun)
        repository.setCurrentRun(progress).bind()
        assertEquals(progress, repository.getStatus().first().currentRun)

        val status = BackupStatus(
            lastStartedAt = startedAt,
            lastFinishedAt = Instant.fromEpochMilliseconds(2L),
            lastSnapshotId = "snapshot-1",
            currentRun = progress,
        )
        repository.setStatus(status).bind()

        val updatedStatus = repository.getStatus().first()
        assertEquals(null, updatedStatus.currentRun)
        assertEquals("snapshot-1", updatedStatus.lastSnapshotId)
    }

    @Test
    fun `current run is not restored from persisted status`() = runTest {
        val store = InMemoryJsonKeyValueStoreStore()
        val startedAt = Instant.fromEpochMilliseconds(1L)
        val progress = BackupRunProgress(
            runId = "run-1",
            trigger = "manual",
            startedAt = startedAt,
            step = BackupStep.BackingUpAttachments,
        )
        val firstRepository = BackupConfigRepositoryImpl(
            store = JsonKeyValueStore(store),
            json = json,
        )

        firstRepository.setCurrentRun(progress).bind()
        assertEquals(progress, firstRepository.getStatus().first().currentRun)
        firstRepository.setStatus(
            BackupStatus(
                lastStartedAt = startedAt,
                lastFinishedAt = Instant.fromEpochMilliseconds(2L),
                lastSnapshotId = "snapshot-1",
                currentRun = progress,
            ),
        ).bind()

        val secondRepository = BackupConfigRepositoryImpl(
            store = JsonKeyValueStore(store),
            json = json,
        )
        val restoredStatus = secondRepository.getStatus().first()

        assertEquals(null, restoredStatus.currentRun)
        assertEquals("snapshot-1", restoredStatus.lastSnapshotId)
    }

    @Test
    fun `mark dirty increments generation and keeps current run in memory`() = runTest {
        val repository = BackupConfigRepositoryImpl(
            store = JsonKeyValueStore(),
            json = json,
        )
        val now = Instant.fromEpochMilliseconds(10L)
        val progress = BackupRunProgress(
            runId = "run-1",
            trigger = "automatic",
            startedAt = Instant.fromEpochMilliseconds(1L),
            step = BackupStep.Preparing,
        )

        repository.setCurrentRun(progress).bind()
        repository.markDirty(now).bind()

        val status = repository.getStatus().first()
        assertEquals(1L, status.changeGeneration)
        assertEquals(now, status.lastChangedAt)
        assertEquals(0L, status.lastSuccessfulBackupChangeGeneration)
        assertTrue(status.isDirty)
        assertEquals(progress, status.currentRun)
    }

    @Test
    fun `set status preserves dirty state`() = runTest {
        val repository = BackupConfigRepositoryImpl(
            store = JsonKeyValueStore(),
            json = json,
        )
        val changedAt = Instant.fromEpochMilliseconds(10L)

        repository.markDirty(changedAt).bind()
        repository.setStatus(
            BackupStatus(
                lastStartedAt = Instant.fromEpochMilliseconds(20L),
                lastFinishedAt = Instant.fromEpochMilliseconds(21L),
                lastSkippedReason = "vault_locked",
            ),
        ).bind()

        val status = repository.getStatus().first()
        assertEquals(1L, status.changeGeneration)
        assertEquals(changedAt, status.lastChangedAt)
        assertEquals(0L, status.lastSuccessfulBackupChangeGeneration)
        assertEquals("vault_locked", status.lastSkippedReason)
        assertTrue(status.isDirty)
    }

    @Test
    fun `successful run clears dirty state through covered generation`() = runTest {
        val repository = BackupConfigRepositoryImpl(
            store = JsonKeyValueStore(),
            json = json,
        )
        val changedAt = Instant.fromEpochMilliseconds(10L)
        val finishedAt = Instant.fromEpochMilliseconds(30L)

        repository.markDirty(changedAt).bind()
        val runStartedGeneration = repository.getStatus().first().changeGeneration
        repository.setStatusAfterSuccessfulRun(
            status = BackupStatus(
                lastStartedAt = Instant.fromEpochMilliseconds(20L),
                lastFinishedAt = finishedAt,
                lastSnapshotId = "snapshot-1",
            ),
            runStartedChangeGeneration = runStartedGeneration,
        ).bind()

        val status = repository.getStatus().first()
        assertEquals(1L, status.changeGeneration)
        assertEquals(1L, status.lastSuccessfulBackupChangeGeneration)
        assertEquals(finishedAt, status.lastSuccessfulBackupAt)
        assertEquals("snapshot-1", status.lastSnapshotId)
        assertFalse(status.isDirty)
    }

    @Test
    fun `successful run keeps newer dirty state`() = runTest {
        val repository = BackupConfigRepositoryImpl(
            store = JsonKeyValueStore(),
            json = json,
        )
        val firstChangeAt = Instant.fromEpochMilliseconds(10L)
        val secondChangeAt = Instant.fromEpochMilliseconds(25L)
        val finishedAt = Instant.fromEpochMilliseconds(30L)

        repository.markDirty(firstChangeAt).bind()
        val runStartedGeneration = repository.getStatus().first().changeGeneration
        repository.markDirty(secondChangeAt).bind()
        repository.setStatusAfterSuccessfulRun(
            status = BackupStatus(
                lastStartedAt = Instant.fromEpochMilliseconds(20L),
                lastFinishedAt = finishedAt,
                lastSnapshotId = "snapshot-1",
            ),
            runStartedChangeGeneration = runStartedGeneration,
        ).bind()

        val status = repository.getStatus().first()
        assertEquals(2L, status.changeGeneration)
        assertEquals(secondChangeAt, status.lastChangedAt)
        assertEquals(1L, status.lastSuccessfulBackupChangeGeneration)
        assertEquals(finishedAt, status.lastSuccessfulBackupAt)
        assertTrue(status.isDirty)
    }

    @Test
    fun `set config marks dirty only when backups can run`() = runTest {
        val repository = BackupConfigRepositoryImpl(
            store = JsonKeyValueStore(),
            json = json,
        )

        repository.setConfig(BackupConfig()).bind()
        assertEquals(0L, repository.getStatus().first().changeGeneration)

        repository.setConfig(
            BackupConfig(
                enabled = true,
                store = BackupStoreConfig.Local(
                    path = "/tmp/keyguard-backups",
                ),
            ),
        ).bind()
        assertEquals(1L, repository.getStatus().first().changeGeneration)

        repository.setConfig(BackupConfig()).bind()
        assertEquals(1L, repository.getStatus().first().changeGeneration)
    }

    private class InMemoryJsonKeyValueStoreStore : JsonKeyValueStoreStore {
        private var state: PersistentMap<String, Any?> = persistentMapOf()

        override fun read() = ioEffect {
            state
        }

        override fun write(state: PersistentMap<String, Any?>) = ioEffect {
            this@InMemoryJsonKeyValueStoreStore.state = state
        }
    }
}
