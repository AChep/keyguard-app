package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.session.VaultSessionLocker
import com.artemchep.keyguard.common.service.vault.SessionReadRepository
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlin.time.Clock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.kodein.di.DirectDI
import org.kodein.di.direct
import org.kodein.di.instance

/**
 * Coordinates backup requests, records run status,
 * and delegates snapshot creation to [BackupRunner].
 */
class BackupRunService(
    private val getVaultSession: GetVaultSession,
    private val sessionReadRepository: SessionReadRepository,
    private val vaultSessionLocker: VaultSessionLocker,
    private val diagnostics: BackupDiagnostics,
) {
    companion object {
        const val TRIGGER_MANUAL = "manual"
        const val TRIGGER_AUTOMATIC = "automatic"

        private const val REASON_BACKUP_NOT_CONFIGURED = "backup_not_configured"
        private const val REASON_VAULT_LOCKED = "vault_locked"
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        getVaultSession = directDI.instance(),
        sessionReadRepository = directDI.instance(),
        vaultSessionLocker = directDI.instance(),
        diagnostics = BackupDiagnostics(logRepository = directDI.instance<LogRepository>()),
    )

    private val mutex = Mutex()

    suspend fun runManual(
        progressReporter: BackupProgressReporter = BackupProgressReporter.NoOp,
    ): BackupStatus = run(
        trigger = TRIGGER_MANUAL,
        sessionPolicy = SessionPolicy.AnyUnlocked,
        requireDirty = false,
        keepSessionAlive = true,
        progressReporter = progressReporter,
    )

    suspend fun runAutomatic(
        progressReporter: BackupProgressReporter = BackupProgressReporter.NoOp,
    ): BackupStatus = run(
        trigger = TRIGGER_AUTOMATIC,
        sessionPolicy = SessionPolicy.AnyUnlocked,
        requireDirty = true,
        keepSessionAlive = true,
        progressReporter = progressReporter,
    )

    private suspend fun run(
        trigger: String,
        sessionPolicy: SessionPolicy,
        requireDirty: Boolean,
        keepSessionAlive: Boolean,
        progressReporter: BackupProgressReporter,
    ): BackupStatus = mutex.withLock {
        val startedAt = Clock.System.now()
        val session = sessionPolicy.getSession()
        if (session == null) {
            val status = BackupStatus(
                lastStartedAt = startedAt,
                lastFinishedAt = Clock.System.now(),
                lastSkippedReason = REASON_VAULT_LOCKED,
            )
            diagnostics.backupSkipped(
                trigger = trigger,
                reason = REASON_VAULT_LOCKED,
            )
            return@withLock status
        }

        val backupConfigRepository: BackupConfigRepository = session.di.direct.instance()
        val runStartedStatus = backupConfigRepository
            .getStatus()
            .first()
        if (requireDirty && !runStartedStatus.isDirty) {
            return@withLock runStartedStatus
        }
        val runStartedChangeGeneration = runStartedStatus.changeGeneration
        val progress = BackupRunProgressContext(
            runId = createBackupRunId(trigger, startedAt),
            trigger = trigger,
            startedAt = startedAt,
            reporter = { progress ->
                backupConfigRepository
                    .setCurrentRun(progress)
                    .bind()
                progressReporter.report(progress)
            },
        )
        try {
            progress.report(BackupStep.Preparing)

            val config = backupConfigRepository.getConfig().first()
            diagnostics.backupRequestStarted(
                trigger = trigger,
                includeAttachments = config.includeAttachments,
                retentionMaxSnapshots = config.retention.maxSnapshots,
            )
            if (!config.canRun()) {
                val status = BackupStatus(
                    lastStartedAt = startedAt,
                    lastFinishedAt = Clock.System.now(),
                    lastSkippedReason = REASON_BACKUP_NOT_CONFIGURED,
                )
                diagnostics.backupSkipped(
                    trigger = trigger,
                    reason = REASON_BACKUP_NOT_CONFIGURED,
                )
                backupConfigRepository.setStatus(status).bind()
                return@withLock backupConfigRepository.getStatus().first()
            }

            try {
                val result = runWithSessionKeepAlive(
                    keepSessionAlive = keepSessionAlive,
                ) {
                    val backupRunner: BackupRunner = session.di.direct.instance()
                    backupRunner.run(
                        config = config,
                        progress = progress,
                    )
                }
                val status = result.toStatus(
                    startedAt = startedAt,
                    finishedAt = Clock.System.now(),
                )
                diagnostics.backupRequestCompleted(
                    trigger = trigger,
                    result = result,
                )
                if (result.skipped) {
                    backupConfigRepository.setStatus(status).bind()
                } else {
                    backupConfigRepository
                        .setStatusAfterSuccessfulRun(
                            status = status,
                            runStartedChangeGeneration = runStartedChangeGeneration,
                        )
                        .bind()
                }
                backupConfigRepository.getStatus().first()
            } catch (e: Exception) {
                e.throwIfFatalOrCancellation()
                diagnostics.backupRequestFailed(
                    trigger = trigger,
                    error = e,
                )
                val status = BackupStatus(
                    lastStartedAt = startedAt,
                    lastFinishedAt = Clock.System.now(),
                    lastErrorMessage = e.message ?: e::class.simpleName,
                )
                backupConfigRepository.setStatus(status).bind()
                backupConfigRepository.getStatus().first()
            }
        } finally {
            withContext(NonCancellable) {
                backupConfigRepository
                    .setCurrentRun(null)
                    .bind()
            }
        }
    }

    private suspend fun <T> runWithSessionKeepAlive(
        keepSessionAlive: Boolean,
        block: suspend () -> T,
    ): T {
        if (!keepSessionAlive) {
            return block()
        }

        return coroutineScope {
            val keepAliveJob = launch {
                vaultSessionLocker.keepAlive()
            }
            try {
                block()
            } finally {
                keepAliveJob.cancelAndJoin()
            }
        }
    }

    private suspend fun SessionPolicy.getSession(): MasterSession.Key? = when (this) {
        SessionPolicy.AnyUnlocked -> getVaultSession()
            .first() as? MasterSession.Key

        SessionPolicy.AuthenticatedInMemory -> sessionReadRepository
            .get()
            .first()
            ?.let { it as? MasterSession.Key }
            ?.takeIf { it.origin == MasterSession.Key.Authenticated }
    }

    private enum class SessionPolicy {
        AnyUnlocked,
        AuthenticatedInMemory,
    }
}
