package com.artemchep.keyguard.feature.sshagent.filter

import androidx.compose.runtime.Composable
import arrow.core.identity
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.SshAgentFilter
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetTags
import com.artemchep.keyguard.common.usecase.PutSshAgentFilter
import com.artemchep.keyguard.feature.home.vault.screen.CreateFilterResult
import com.artemchep.keyguard.feature.home.vault.screen.FilterParams
import com.artemchep.keyguard.feature.home.vault.screen.OurFilterResult
import com.artemchep.keyguard.feature.home.vault.screen.ah
import com.artemchep.keyguard.feature.home.vault.search.filter.FilterHolder
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.kodein.di.DirectDI
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun produceSshAgentFiltersState() = with(localDI().direct) {
    produceSshAgentFiltersState(
        directDI = this,
        getSshAgentFilter = instance(),
        putSshAgentFilter = instance(),
        getCiphers = instance(),
        getAccounts = instance(),
        getProfiles = instance(),
        getTags = instance(),
        getFolders = instance(),
        getCollections = instance(),
        getOrganizations = instance(),
    )
}

@Composable
fun produceSshAgentFiltersState(
    directDI: DirectDI,
    getSshAgentFilter: GetSshAgentFilter,
    putSshAgentFilter: PutSshAgentFilter,
    getCiphers: GetCiphers,
    getAccounts: GetAccounts,
    getProfiles: GetProfiles,
    getTags: GetTags,
    getFolders: GetFolders,
    getCollections: GetCollections,
    getOrganizations: GetOrganizations,
): Loadable<SshAgentFiltersState> = produceScreenState(
    key = "ssh_agent_filters",
    initial = Loadable.Loading,
) {
    val savedFilterFlow = getSshAgentFilter()
        .map { it.normalize() }
        .distinctUntilChanged()

    val initialSavedFilter = savedFilterFlow.first()
    val savedFilterStateFlow = savedFilterFlow
        .stateIn(screenScope, SharingStarted.WhileSubscribed(), initialSavedFilter)
    val initialPending = FilterHolder(
        state = initialSavedFilter.state,
    )
    val pendingSink = mutablePersistedFlow(
        key = "pending_filter",
    ) { initialPending }

    val emptyPending = FilterHolder(
        state = emptyMap(),
    )

    val onClearPending = onClick {
        pendingSink.value = emptyPending
    }

    val onTogglePending = { sectionId: String, filters: Set<com.artemchep.keyguard.common.model.DFilter.Primitive> ->
        pendingSink.update { holder ->
            val activeFilters = holder.state.getOrElse(sectionId) { emptySet() }
            val pendingFilters = filters
                .filter { it !in activeFilters }

            val newFilters = if (pendingFilters.isNotEmpty()) {
                activeFilters + pendingFilters
            } else {
                activeFilters - filters
            }
            holder.copy(
                state = holder.state + (sectionId to newFilters),
            )
        }
    }

    val onApplyPending = { state: Map<String, Set<com.artemchep.keyguard.common.model.DFilter.Primitive>> ->
        pendingSink.update { holder ->
            if (holder.state == state) {
                return@update emptyPending
            }
            holder.copy(state = state)
        }
    }

    val input = CreateFilterResult(
        filterFlow = pendingSink,
        onToggle = onTogglePending,
        onApply = onApplyPending,
        onClear = { pendingSink.value = emptyPending },
        onSave = { _ -> },
    )

    val allSshKeysFlow = getCiphers()
        .map { ciphers ->
            ciphers.filter { it.type == DSecret.Type.SshKey }
        }
        .distinctUntilChanged()

    val filteredSshKeysFlow = combine(
        allSshKeysFlow,
        pendingSink,
    ) { ciphers, filterHolder ->
        ciphers to filterHolder
    }
        .mapLatest { (ciphers, filterHolder) ->
            if (filterHolder.state.isEmpty()) {
                return@mapLatest ciphers
            }
            val predicate = filterHolder.filter.prepare(
                directDI = directDI,
                ciphers = ciphers,
            )
            ciphers.filter(predicate)
        }
        .distinctUntilChanged()

    val filterListFlow = ah(
        directDI = directDI,
        outputGetter = ::identity,
        outputFlow = filteredSshKeysFlow,
        accountGetter = ::identity,
        accountFlow = getAccounts(),
        profileFlow = getProfiles(),
        cipherGetter = ::identity,
        cipherFlow = allSshKeysFlow,
        tagGetter = ::identity,
        tagFlow = getTags(),
        folderGetter = ::identity,
        folderFlow = getFolders(),
        collectionGetter = ::identity,
        collectionFlow = getCollections(),
        organizationGetter = ::identity,
        organizationFlow = getOrganizations(),
        input = input,
        params = FilterParams(),
    )
        .stateIn(this, SharingStarted.WhileSubscribed(), OurFilterResult())

    val normalizedPendingFilterFlow = pendingSink
        .map { holder ->
            SshAgentFilter(holder.state).normalize()
        }
        .distinctUntilChanged()

    val canResetFlow = normalizedPendingFilterFlow
        .map { it.isActive }
        .distinctUntilChanged()

    combine(
        filterListFlow,
        filteredSshKeysFlow.map { it.size }.distinctUntilChanged(),
        combine(
            savedFilterStateFlow,
            normalizedPendingFilterFlow,
        ) { saved, pending ->
            pending != saved
        }
            .distinctUntilChanged(),
        canResetFlow,
        normalizedPendingFilterFlow,
    ) { filterList, count, isDirty, canReset, pendingNormalized ->
        val onSave = if (isDirty) {
            onClick {
                putSshAgentFilter(pendingNormalized)
                    .launchIn(appScope)
                navigatePopSelf()
            }
        } else {
            null
        }
        val onReset = if (canReset) {
            onClearPending
        } else {
            null
        }
        SshAgentFiltersState(
            count = count,
            filters = filterList.items,
            onSave = onSave,
            onReset = onReset,
        )
    }
        .flowOn(Dispatchers.Default)
        .map { state ->
            Loadable.Ok(state)
        }
}
