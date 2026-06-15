package com.artemchep.keyguard.feature.home.vault.search.engine

import androidx.compose.runtime.MutableState
import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.common.usecase.GetVaultSearchIndex
import com.artemchep.keyguard.common.usecase.GetVaultSearchQualifierCatalog
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.search.query.VaultSearchQualifierCatalog
import com.artemchep.keyguard.feature.home.vault.search.query.VaultSearchQualifierSuggestion
import com.artemchep.keyguard.feature.home.vault.search.query.bestVaultSearchQualifierSuggestion
import com.artemchep.keyguard.feature.home.vault.search.query.highlight.QueryHighlighting
import com.artemchep.keyguard.feature.home.vault.search.query.highlight.VaultSearchQueryHighlighter
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledQueryPlan
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.search.search.debounceSearch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn

internal class VaultSearchQueryHandle(
    val querySink: MutableStateFlow<String>,
    val queryState: MutableState<String>,
    val queryFocusSink: EventFlow<Unit>,
    val queryPairFlow: Flow<Pair<String, String>>,
    val debouncedQueryFlow: Flow<String?>,
    val queryHighlightingFlow: Flow<QueryHighlighting>,
    val queryQualifierSuggestionFlow: Flow<VaultSearchQualifierSuggestion?>,
    val searchContextFlow: Flow<VaultSearchContext?>,
    val queryRevisionFlow: Flow<Int>,
)

internal data class VaultSearchContext(
    val searchIndex: VaultSearchIndex,
    val queryPlan: CompiledQueryPlan?,
)

internal fun RememberStateFlowScope.vaultSearchQueryHandle(
    key: String,
    searchBy: VaultRoute.Args.SearchBy,
    getVaultSearchQualifierCatalog: GetVaultSearchQualifierCatalog,
    getVaultSearchIndex: GetVaultSearchIndex,
    surface: String,
    queryHighlighter: VaultSearchQueryHighlighter,
    sharingStarted: SharingStarted,
): VaultSearchQueryHandle {
    val querySink = mutablePersistedFlow(key) { "" }
    val queryState = mutableComposeState(querySink)
    val queryFocusSink = EventFlow<Unit>()
    val queryPairFlow = querySink
        .map { it to it.trim() }
        .shareIn(this, sharingStarted, replay = 1)
    val qualifierCatalogFlow = getVaultSearchQualifierCatalog()
        .shareIn(this, sharingStarted, replay = 1)
    val queryHighlightingFlow = combine(
        querySink,
        qualifierCatalogFlow,
    ) { query, qualifierCatalog ->
        vaultSearchQueryHighlighting(
            query = query,
            searchBy = searchBy,
            queryHighlighter = queryHighlighter,
            qualifierCatalog = qualifierCatalog,
        )
    }.shareIn(this, sharingStarted, replay = 1)
    val queryQualifierSuggestionFlow = combine(
        querySink,
        qualifierCatalogFlow,
    ) { query, qualifierCatalog ->
        bestVaultSearchQualifierSuggestion(
            query = query,
            catalog = qualifierCatalog,
        )
    }.shareIn(this, sharingStarted, replay = 1)
    val debouncedQueryFlow = queryPairFlow
        .vaultSearchDebouncedQueryFlow()
        .shareIn(this, sharingStarted, replay = 1)
    val searchIndexFlow = getVaultSearchIndex(surface)
        .shareIn(this, sharingStarted, replay = 1)
    val searchContextFlow = debouncedQueryFlow
        .vaultSearchContextFlow(
            searchIndexFlow = searchIndexFlow,
            qualifierCatalogFlow = qualifierCatalogFlow,
            searchBy = searchBy,
        )
        .shareIn(this, sharingStarted, replay = 1)
    val queryRevisionFlow = searchContextFlow
        .map { it?.queryPlan?.id ?: 0 }
        .shareIn(this, sharingStarted, replay = 1)
    return VaultSearchQueryHandle(
        querySink = querySink,
        queryState = queryState,
        queryFocusSink = queryFocusSink,
        queryPairFlow = queryPairFlow,
        debouncedQueryFlow = debouncedQueryFlow,
        queryHighlightingFlow = queryHighlightingFlow,
        queryQualifierSuggestionFlow = queryQualifierSuggestionFlow,
        searchContextFlow = searchContextFlow,
        queryRevisionFlow = queryRevisionFlow,
    )
}

internal fun vaultSearchQueryHighlighting(
    query: String,
    searchBy: VaultRoute.Args.SearchBy,
    queryHighlighter: VaultSearchQueryHighlighter,
    qualifierCatalog: VaultSearchQualifierCatalog,
): QueryHighlighting = queryHighlighter.highlight(
    query = query,
    searchBy = searchBy,
    qualifierCatalog = qualifierCatalog,
)

internal fun Flow<String>.mapVaultSearchQueryHighlighting(
    searchBy: VaultRoute.Args.SearchBy,
    queryHighlighter: VaultSearchQueryHighlighter,
    qualifierCatalog: VaultSearchQualifierCatalog,
): Flow<QueryHighlighting> = map { query ->
    vaultSearchQueryHighlighting(
        query = query,
        searchBy = searchBy,
        queryHighlighter = queryHighlighter,
        qualifierCatalog = qualifierCatalog,
    )
}

internal fun Flow<Pair<String, String>>.vaultSearchDebouncedQueryFlow(): Flow<String?> = this
    .debounceSearch { (_, queryTrimmed) -> queryTrimmed }
    .map { (_, queryTrimmed) ->
        queryTrimmed.takeIf(String::isNotEmpty)
    }

internal fun Flow<String?>.vaultSearchContextFlow(
    searchIndexFlow: Flow<VaultSearchIndex>,
    qualifierCatalogFlow: Flow<VaultSearchQualifierCatalog>,
    searchBy: VaultRoute.Args.SearchBy,
): Flow<VaultSearchContext?> = flatMapLatest { queryTrimmed ->
    if (queryTrimmed == null) {
        flowOf(null)
    } else {
        combine(
            searchIndexFlow,
            qualifierCatalogFlow,
        ) { searchIndex, qualifierCatalog ->
            VaultSearchContext(
                searchIndex = searchIndex,
                queryPlan = searchIndex.compile(
                    query = queryTrimmed,
                    searchBy = searchBy,
                    qualifierCatalog = qualifierCatalog,
                ),
            )
        }
    }
}

internal fun vaultSearchFilteredItemsFlow(
    itemsFlow: Flow<List<VaultItem2.Item>>,
    searchContextFlow: Flow<VaultSearchContext?>,
    highlightBackgroundColor: Color,
    highlightContentColor: Color,
): Flow<List<VaultItem2.Item>> = searchContextFlow
    .flatMapLatest { searchContext ->
        if (searchContext?.queryPlan == null) {
            itemsFlow
        } else {
            itemsFlow.mapLatest { candidates ->
                searchContext.searchIndex.evaluate(
                    plan = searchContext.queryPlan,
                    candidates = candidates,
                    highlightBackgroundColor = highlightBackgroundColor,
                    highlightContentColor = highlightContentColor,
                )
            }
        }
    }

internal fun vaultSearchTraceFlow(
    surface: String,
    debouncedQueryFlow: Flow<String?>,
    searchContextFlow: Flow<VaultSearchContext?>,
    activeSortFlow: Flow<String>,
    finalResultCountFlow: Flow<Int>,
    rawItemCountFlow: Flow<Int?> = flowOf(null),
    routeFilteredCountFlow: Flow<Int?> = flowOf(null),
    filterFilteredCountFlow: Flow<Int?> = flowOf(null),
    preferredCountFlow: Flow<Int?> = flowOf(null),
): Flow<SurfaceTraceEvent?> = debouncedQueryFlow
    .flatMapLatest { queryTrimmed ->
        if (queryTrimmed == null) {
            flowOf(null)
        } else {
            combine(
                rawItemCountFlow,
                routeFilteredCountFlow,
            ) { rawItemCount, routeFilteredCount ->
                TraceCountMetrics(
                    rawItemCount = rawItemCount,
                    routeFilteredCount = routeFilteredCount,
                )
            }.combine(
                combine(
                    filterFilteredCountFlow,
                    preferredCountFlow,
                    activeSortFlow,
                    searchContextFlow,
                ) { filterFilteredCount, preferredCount, activeSort, searchContext ->
                    TraceSearchMetrics(
                        filterFilteredCount = filterFilteredCount,
                        preferredCount = preferredCount,
                        activeSort = activeSort,
                        searchContext = searchContext,
                    )
                },
            ) { counts, search ->
                TraceMetrics(
                    rawItemCount = counts.rawItemCount,
                    routeFilteredCount = counts.routeFilteredCount,
                    filterFilteredCount = search.filterFilteredCount,
                    preferredCount = search.preferredCount,
                    activeSort = search.activeSort,
                    searchContext = search.searchContext,
                )
            }.combine(finalResultCountFlow) { metrics, finalResultCount ->
                SurfaceTraceEvent(
                    surface = surface,
                    rawQuery = queryTrimmed,
                    rawItemCount = metrics.rawItemCount,
                    routeFilteredCount = metrics.routeFilteredCount,
                    filterFilteredCount = metrics.filterFilteredCount,
                    preferredCount = metrics.preferredCount,
                    activeSort = metrics.activeSort,
                    rankingMode = rankingModeForTrace(
                        metrics.searchContext?.queryPlan?.hasScoringClauses == true,
                    ),
                    finalResultCount = finalResultCount,
                )
            }
        }
    }

private data class TraceCountMetrics(
    val rawItemCount: Int?,
    val routeFilteredCount: Int?,
)

private data class TraceSearchMetrics(
    val filterFilteredCount: Int?,
    val preferredCount: Int?,
    val activeSort: String,
    val searchContext: VaultSearchContext?,
)

private data class TraceMetrics(
    val rawItemCount: Int?,
    val routeFilteredCount: Int?,
    val filterFilteredCount: Int?,
    val preferredCount: Int?,
    val activeSort: String,
    val searchContext: VaultSearchContext?,
)
