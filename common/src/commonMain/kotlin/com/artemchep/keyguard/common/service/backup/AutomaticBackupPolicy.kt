package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.vault.SessionReadRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.direct
import org.kodein.di.instance

@OptIn(ExperimentalCoroutinesApi::class)
fun automaticBackupScheduleStateFlow(
    sessionReadRepository: SessionReadRepository,
    getBackupConfigRepository: (MasterSession.Key) -> BackupConfigRepository = { session ->
        session.di.direct.instance()
    },
): Flow<AutomaticBackupScheduleState> = sessionReadRepository.get()
    .flatMapLatest { session ->
        if (!AutomaticBackupPolicy.isAuthenticatedInMemory(session)) {
            flowOf(
                AutomaticBackupPolicy.createState(
                    config = BackupConfig(),
                    status = BackupStatus(),
                    session = session,
                ),
            )
        } else {
            val key = session as MasterSession.Key
            val backupConfigRepository = getBackupConfigRepository(key)
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
    ): Boolean = session is MasterSession.Key
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
