package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.vault.SessionReadRepository
import com.artemchep.keyguard.common.worker.Wrker
import com.artemchep.keyguard.platform.lifecycle.LeLifecycleState
import com.artemchep.keyguard.platform.lifecycle.onState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import org.kodein.di.DirectDI
import org.kodein.di.direct
import org.kodein.di.instance

/**
 * Desktop lifecycle worker that observes schedule state
 * and triggers [BackupRunService.runAutomatic].
 */
@OptIn(FlowPreview::class)
class BackupSchedulerWorker(
    private val sessionReadRepository: SessionReadRepository,
    private val getBackupConfigRepository: (MasterSession.Key) -> BackupConfigRepository,
    private val runAutomatic: suspend () -> Unit,
) : Wrker {
    constructor(
        sessionReadRepository: SessionReadRepository,
        backupRunService: BackupRunService,
    ) : this(
        sessionReadRepository = sessionReadRepository,
        getBackupConfigRepository = { session ->
            session.di.direct.instance()
        },
        runAutomatic = {
            backupRunService.runAutomatic()
        },
    )

    constructor(
        directDI: DirectDI,
    ) : this(
        sessionReadRepository = directDI.instance(),
        backupRunService = directDI.instance(),
    )

    override fun start(
        scope: CoroutineScope,
        flow: Flow<LeLifecycleState>,
    ) {
        flow
            .onState(LeLifecycleState.STARTED) {
                automaticBackupScheduleStateFlow(
                    sessionReadRepository = sessionReadRepository,
                    getBackupConfigRepository = getBackupConfigRepository,
                )
                    .debounce(AutomaticBackupPolicy.DEBOUNCE_DELAY_MS)
                    .filter { it.shouldRun }
                    .collect {
                        runAutomatic()
                    }
            }
            .launchIn(scope)
    }
}
