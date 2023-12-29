package com.artemchep.keyguard.feature.search.search

import androidx.compose.runtime.MutableState
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.home.vault.search.IndexedText
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.ui.theme.searchHighlightBackgroundColor
import com.artemchep.keyguard.ui.theme.searchHighlightContentColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

const val SEARCH_DEBOUNCE = 88L

class SearchQueryHandle(
    val scope: RememberStateFlowScope,
    val querySink: MutableStateFlow<String>,
    val queryState: MutableState<String>,
    val queryIndexed: Flow<IndexedText?>,
) {
}

fun RememberStateFlowScope.searchQueryHandle(
    key: String,
): SearchQueryHandle {
    val querySink = mutablePersistedFlow(key) { "" }
    val queryState = mutableComposeState(querySink)

    val queryIndexedFlow = querySink
        .debounce(SEARCH_DEBOUNCE)
        .map { query ->
            val queryTrimmed = query.trim()
            if (queryTrimmed.isEmpty()) return@map null
            IndexedText(
                text = queryTrimmed,
            )
        }

    return SearchQueryHandle(
        scope = this,
        querySink = querySink,
        queryState = queryState,
        queryIndexed = queryIndexedFlow,
    )
}

suspend fun <T> RememberStateFlowScope.searchFilter(
    handle: SearchQueryHandle,
    transform: (TextFieldModel2, Int) -> T,
) = handle.querySink
    .map { query ->
        val revision = query.trim().hashCode()
        val model = TextFieldModel2(
            state = handle.queryState,
            text = query,
            onChange = handle.queryState::value::set,
        )
        transform(
            model,
            revision,
        )
    }
    .stateIn(screenScope)

fun <T> Flow<List<IndexedModel<T>>>.mapSearch(
    handle: SearchQueryHandle,
    transform: (T, IndexedText.FindResult) -> T,
) = this
    .combine(handle.queryIndexed) { items, query -> items to query }
    .mapLatest { (items, query) ->
        if (query == null) {
            return@mapLatest items.map { it.model } to 0
        }

        val filteredItems = items
            .search(
                query,
                highlightBackgroundColor = handle.scope.colorScheme.searchHighlightBackgroundColor,
                highlightContentColor = handle.scope.colorScheme.searchHighlightContentColor,
                transform = transform,
            )
        filteredItems to query.text.hashCode()
    }

