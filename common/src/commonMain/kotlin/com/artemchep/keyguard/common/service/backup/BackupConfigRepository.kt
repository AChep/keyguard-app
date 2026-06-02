package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.io.IO
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

interface BackupConfigRepository {
    fun getConfig(): Flow<BackupConfig>

    fun setConfig(
        config: BackupConfig,
    ): IO<Unit>

    fun getStatus(): Flow<BackupStatus>

    fun setStatus(
        status: BackupStatus,
    ): IO<Unit>

    fun setStatusAfterSuccessfulRun(
        status: BackupStatus,
        runStartedChangeGeneration: Long,
    ): IO<Unit>

    fun setCurrentRun(
        progress: BackupRunProgress?,
    ): IO<Unit>

    fun markDirty(
        now: Instant = Clock.System.now(),
    ): IO<BackupStatus>
}
