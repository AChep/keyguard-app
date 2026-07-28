package com.artemchep.keyguard.feature.search.search

import arrow.core.identity
import com.artemchep.keyguard.feature.auth.common.TextCell
import com.artemchep.keyguard.feature.auth.common.TextFieldHandle
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.common.textFieldHandle
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.home.vault.search.IndexedText
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.ui.theme.searchHighlightBackgroundColor
import com.artemchep.keyguard.ui.theme.searchHighlightContentColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

const val SEARCH_DEBOUNCE = 88L

const val SEARCH_DEBOUNCE_LONG = 200L

class SearchQueryHandle(
    val scope: RememberStateFlowScope,
    /** Owns the canonical query cell; shared field semantics. */
    val queryField: TextFieldHandle,
    val queryIndexed: Flow<IndexedText?>,
    val focusSink: EventFlow<Unit>,
    val revisionFlow: Flow<Int>,
) {
    val querySink: MutableStateFlow<TextCell>
        get() = queryField.sink

    /** Reflects a user edit; keeps the text revision. */
    fun onChange(text: String) = queryField.onChange(text)

    /**
     * Writes the text as a command (clear, suggestion, restore); bumps the
     * revision so UI edge buffers adopt the new text unconditionally.
     */
    fun setText(text: String) = queryField.setText(text)
}

fun RememberStateFlowScope.searchQueryHandle(
    key: String,
    revisionFlow: Flow<Int> = flowOf(0),
): SearchQueryHandle {
    val queryField = textFieldHandle(key)

    val queryIndexedFlow = queryField.sink
        .map { it.text }
        .debounceSearch(::identity)
        .map { query ->
            val queryTrimmed = query.trim()
            if (queryTrimmed.isEmpty()) return@map null
            IndexedText(
                text = queryTrimmed,
            )
        }

    val focusSink = EventFlow<Unit>()
    return SearchQueryHandle(
        scope = this,
        queryField = queryField,
        queryIndexed = queryIndexedFlow,
        focusSink = focusSink,
        revisionFlow = revisionFlow,
    )
}

suspend fun <T> RememberStateFlowScope.searchFilter(
    handle: SearchQueryHandle,
    transform: (TextFieldModel, Int) -> T,
) = combine(
    handle.querySink,
    handle.revisionFlow,
) { cell, rev ->
    val revision = rev xor cell.text.trim().hashCode()
    val model = TextFieldModel(
        text = cell.text,
        textRevision = cell.revision,
        onChange = handle::onChange,
        onSetText = handle::setText,
        focusFlow = handle.focusSink,
    )
    transform(
        model,
        revision,
    )
}
    .stateIn(screenScope)

fun <T> Flow<T>.debounceSearch(
    getter: (T) -> String,
) = this
    .debounce {
        val query = getter(it)
        when {
            query.isEmpty() -> 0L
            query.length <= 3 -> SEARCH_DEBOUNCE_LONG
            else -> SEARCH_DEBOUNCE
        }
    }

fun <T> Flow<List<IndexedModel<T>>>.mapSearch(
    handle: SearchQueryHandle,
    transform: (T, IndexedText.FindResult) -> T,
) = combine(
    this,
    handle.queryIndexed,
    handle.revisionFlow,
) { items, query, rev ->
    Triple(items, query, rev)
}
    .mapLatest { (items, query, rev) ->
        if (query == null) {
            return@mapLatest items.map { it.model } to rev
        }

        val filteredItems = items
            .search(
                query,
                highlightBackgroundColor = handle.scope.colorScheme.searchHighlightBackgroundColor,
                highlightContentColor = handle.scope.colorScheme.searchHighlightContentColor,
                transform = transform,
            )
        val revision = rev xor query.text.hashCode()
        filteredItems to revision
    }

