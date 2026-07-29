package com.artemchep.keyguard.common.service.exposedaccount.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.service.exposedaccount.ExposedAccount
import com.artemchep.keyguard.common.service.exposedaccount.ExposedAccountRepository
import com.artemchep.keyguard.common.service.exposedaccount.ExposedAccountSyncer
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetProfiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.kodein.di.DirectDI
import org.kodein.di.instance

class ExposedAccountSyncerImpl(
    private val getProfiles: GetProfiles,
    private val exposedAccountRepository: ExposedAccountRepository,
    private val logRepository: LogRepository,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ExposedAccountSyncer {
    companion object {
        private const val TAG = "ExposedAccountSyncer"
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        getProfiles = directDI.instance(),
        exposedAccountRepository = directDI.instance(),
        logRepository = directDI.instance(),
    )

    override fun launch(scope: CoroutineScope): Job = scope.launch {
        val syncStateFlow = getProfiles()
            .map { profiles -> profiles.toSyncState() }
            .distinctUntilChanged()
            .flowOn(defaultDispatcher)

        syncStateFlow.collectLatest { state ->
            // `runCatchingNonFatal` rather than a bare catch: a failed mirror write
            // must not take the collector down, but cancellation and process-fatal
            // errors have to keep propagating.
            runCatchingNonFatal {
                exposedAccountRepository
                    .replaceAll(
                        accounts = state.accounts,
                        allAccountIds = state.allAccountIds,
                    )
                    .bind()
            }.onFailure { e ->
                logRepository.post(
                    tag = TAG,
                    message = "Failed to sync exposed accounts: ${e.message}",
                    level = LogLevel.ERROR,
                )
            }
        }
    }

    /**
     * What the mirror should contain for [this] profile list.
     *
     * A plain value type so [distinctUntilChanged] can compare it structurally —
     * the vault emits a new profile list for changes the mirror does not care
     * about, and rewriting the tables on each of those would be pure churn.
     */
    private fun List<DProfile>.toSyncState(): SyncState = SyncState(
        // Hidden accounts are deliberately NOT mirrored: `hidden` means "do not
        // use during autofill", and that has to hold before unlock too.
        accounts = filter { !it.hidden }
            .map { profile ->
                ExposedAccount(
                    accountId = profile.accountId,
                    name = profile.name,
                    email = profile.email,
                    host = profile.accountHost,
                )
            },
        // Entry ids, however, are minted for every account including hidden ones,
        // so that a stale pick of a now-hidden account still resolves and can be
        // explained rather than rejected as an unknown caller.
        allAccountIds = mapTo(mutableSetOf()) { it.accountId },
    )

    private data class SyncState(
        val accounts: List<ExposedAccount>,
        val allAccountIds: Set<String>,
    )
}
