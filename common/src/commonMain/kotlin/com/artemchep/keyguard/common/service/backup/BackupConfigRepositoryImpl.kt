package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.keyvalue.KeyValuePreference
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.VaultSettingsKeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.getSerializable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Clock
import kotlin.time.Instant

class BackupConfigRepositoryImpl(
    store: KeyValueStore,
    json: Json,
) : BackupConfigRepository {
    companion object {
        private const val KEY_CONFIG = "backup.config"
        private const val KEY_STATUS = "backup.status"
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        store = directDI.instance<VaultSettingsKeyValueStore>(),
        json = directDI.instance(),
    )

    private val config: KeyValuePreference<BackupConfig> = store.getSerializable(
        json = json,
        key = KEY_CONFIG,
        defaultValue = BackupConfig(),
    )

    private val status: KeyValuePreference<BackupStatus> = store.getSerializable(
        json = json,
        key = KEY_STATUS,
        defaultValue = BackupStatus(),
    )

    private val currentRun = MutableStateFlow<BackupRunProgress?>(null)

    private val mutex = Mutex()

    override fun getConfig(): Flow<BackupConfig> = config

    override fun setConfig(
        config: BackupConfig,
    ): IO<Unit> = ioEffect {
        mutex.withLock {
            val previousConfig = this@BackupConfigRepositoryImpl.config.first()
            this@BackupConfigRepositoryImpl.config
                .setAndCommit(config)
                .bind()
            if (config.canRun() && config != previousConfig) {
                markDirtyLocked(Clock.System.now())
            }
        }
    }

    override fun getStatus(): Flow<BackupStatus> = combine(
        status,
        currentRun,
    ) { persistedStatus, progress ->
        persistedStatus.copy(
            currentRun = progress,
        )
    }

    override fun setStatus(
        status: BackupStatus,
    ): IO<Unit> = ioEffect {
        mutex.withLock {
            val current = persistedStatus()
            currentRun.value = null
            persistStatus(status.withDirtyStateFrom(current))
        }
    }

    override fun setStatusAfterSuccessfulRun(
        status: BackupStatus,
        runStartedChangeGeneration: Long,
    ): IO<Unit> = ioEffect {
        mutex.withLock {
            val current = persistedStatus()
            val lastSuccessfulBackupChangeGeneration = maxOf(
                current.lastSuccessfulBackupChangeGeneration,
                runStartedChangeGeneration,
            )
            currentRun.value = null
            persistStatus(
                status
                    .withDirtyStateFrom(current)
                    .copy(
                        lastSuccessfulBackupAt = status.lastFinishedAt,
                        lastSuccessfulBackupChangeGeneration = lastSuccessfulBackupChangeGeneration,
                    ),
            )
        }
    }

    override fun setCurrentRun(
        progress: BackupRunProgress?,
    ): IO<Unit> = ioEffect {
        currentRun.value = progress
    }

    override fun markDirty(
        now: Instant,
    ): IO<BackupStatus> = ioEffect {
        mutex.withLock {
            markDirtyLocked(now)
        }
    }

    private suspend fun markDirtyLocked(
        now: Instant,
    ): BackupStatus {
        val current = persistedStatus()
        val updated = current.copy(
            changeGeneration = current.changeGeneration + 1L,
            lastChangedAt = now,
            currentRun = null,
        )
        persistStatus(updated)
        return updated.copy(
            currentRun = currentRun.value,
        )
    }

    private suspend fun persistedStatus(): BackupStatus = status.first()

    private suspend fun persistStatus(
        status: BackupStatus,
    ) {
        this.status
            .setAndCommit(
                status.copy(
                    currentRun = null,
                ),
            )
            .bind()
    }

    private fun BackupStatus.withDirtyStateFrom(
        current: BackupStatus,
    ): BackupStatus = copy(
        changeGeneration = current.changeGeneration,
        lastChangedAt = current.lastChangedAt,
        lastSuccessfulBackupAt = current.lastSuccessfulBackupAt,
        lastSuccessfulBackupChangeGeneration = current.lastSuccessfulBackupChangeGeneration,
        currentRun = null,
    )
}