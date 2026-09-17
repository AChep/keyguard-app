package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.session.BackupConfigSessionAccess
import com.artemchep.keyguard.common.service.vault.SessionReadRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalCoroutinesApi::class)
fun automaticBackupScheduleStateFlow(
    sessionReadRepository: SessionReadRepository,
    getBackupConfigRepository: BackupConfigSessionAccess,
): Flow<AutomaticBackupScheduleState> = sessionReadRepository.get()
    .flatMapLatest { session ->
        val backupConfigRepository = (session as? MasterSession.Key)
            ?.let(getBackupConfigRepository::invoke)
        if (!AutomaticBackupPolicy.isAuthenticatedInMemory(session) || backupConfigRepository == null) {
            flowOf(
                AutomaticBackupPolicy.createState(
                    config = BackupConfig(),
                    status = BackupStatus(),
                    session = session,
                ),
            )
        } else {
            combine(
                backupConfigRepository.getConfig(),
                backupConfigRepository.getStatus(),
            ) { config, status ->
                AutomaticBackupPolicy.createState(
                    config = config,
                    status = status,
                    session = session,
                )
            }
        }
    }
    .distinctUntilChanged()

object AutomaticBackupPolicy {
    const val DEBOUNCE_DELAY_MS = 5_000L

    fun createState(
        config: BackupConfig,
        status: BackupStatus,
        session: MasterSession?,
    ): AutomaticBackupScheduleState = AutomaticBackupScheduleState(
        config = config,
        changeGeneration = status.changeGeneration,
        lastSuccessfulBackupChangeGeneration =
            status.lastSuccessfulBackupChangeGeneration,
        authenticated = isAuthenticatedInMemory(session),
    )

    fun isAuthenticatedInMemory(
        session: MasterSession?,
    ): Boolean = session is MasterSession.Key && session.session.active.value
}

data class AutomaticBackupScheduleState(
    val config: BackupConfig,
    val changeGeneration: Long,
    val lastSuccessfulBackupChangeGeneration: Long,
    val authenticated: Boolean,
) {
    val shouldRun: Boolean
        get() = config.canRun() &&
                authenticated &&
                changeGeneration > lastSuccessfulBackupChangeGeneration
}
