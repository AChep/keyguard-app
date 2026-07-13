package com.artemchep.keyguard.feature.gpgagent.filter

import androidx.compose.runtime.Composable
import arrow.core.identity
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.GpgAgentFilter
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.gpgagent.isEligibleForGpgAgent
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetGpgAgentFilter
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetTags
import com.artemchep.keyguard.common.usecase.PutGpgAgentFilter
import com.artemchep.keyguard.feature.home.vault.screen.CreateFilterResult
import com.artemchep.keyguard.feature.home.vault.screen.FilterParams
import com.artemchep.keyguard.feature.home.vault.screen.OurFilterResult
import com.artemchep.keyguard.feature.home.vault.screen.createFilterItemsFlow
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
fun produceGpgAgentFiltersState() = with(localDI().direct) {
    produceGpgAgentFiltersState(
        directDI = this,
        getGpgAgentFilter = instance(),
        putGpgAgentFilter = instance(),
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
fun produceGpgAgentFiltersState(
    directDI: DirectDI,
    getGpgAgentFilter: GetGpgAgentFilter,
    putGpgAgentFilter: PutGpgAgentFilter,
    getCiphers: GetCiphers,
    getAccounts: GetAccounts,
    getProfiles: GetProfiles,
    getTags: GetTags,
    getFolders: GetFolders,
    getCollections: GetCollections,
    getOrganizations: GetOrganizations,
): Loadable<GpgAgentFiltersState> = produceScreenState(
    key = "gpg_agent_filters",
    initial = Loadable.Loading,
) {
    val savedFilterFlow = getGpgAgentFilter()
        .map { it.normalize() }
        .distinctUntilChanged()

    val initialSavedFilter = savedFilterFlow.first()
    val savedFilterStateFlow = savedFilterFlow
        .stateIn(screenScope, SharingStarted.WhileSubscribed(), initialSavedFilter)
    val initialPending = FilterHolder(
        state = initialSavedFilter.state,
    )
    val pendingSink = mutablePersistedFlow<FilterHolder, String>(
        key = "pending_filter",
        serialize = { json, value ->
            json.encodeToString(value)
        },
        deserialize = { json, value ->
            json.decodeFromString(value)
        },
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

    val allGpgKeysFlow = getCiphers()
        .map { ciphers ->
            ciphers.filter { it.isEligibleForGpgAgent() }
        }
        .distinctUntilChanged()

    val filteredGpgKeysFlow = combine(
        allGpgKeysFlow,
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

    val filterListFlow = createFilterItemsFlow(
        directDI = directDI,
        outputGetter = ::identity,
        outputFlow = filteredGpgKeysFlow,
        accountGetter = ::identity,
        accountFlow = getAccounts(),
        profileFlow = getProfiles(),
        cipherGetter = ::identity,
        cipherFlow = allGpgKeysFlow,
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
            GpgAgentFilter(holder.state).normalize()
        }
        .distinctUntilChanged()

    val canResetFlow = normalizedPendingFilterFlow
        .map { it.isActive }
        .distinctUntilChanged()

    combine(
        filterListFlow,
        filteredGpgKeysFlow.map { it.size }.distinctUntilChanged(),
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
                putGpgAgentFilter(pendingNormalized)
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
        GpgAgentFiltersState(
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
