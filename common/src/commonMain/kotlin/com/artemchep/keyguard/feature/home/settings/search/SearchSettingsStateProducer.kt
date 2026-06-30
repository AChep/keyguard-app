package com.artemchep.keyguard.feature.home.settings.search

import androidx.compose.runtime.Composable
import arrow.core.identity
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.util.flow.combineToList
import com.artemchep.keyguard.feature.auth.common.TextCell
import com.artemchep.keyguard.feature.auth.common.textFieldHandle
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.generator.emailrelay.EmailRelayListState
import com.artemchep.keyguard.feature.home.settings.hub
import com.artemchep.keyguard.feature.home.vault.search.findAlike
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.search.SEARCH_DEBOUNCE
import com.artemchep.keyguard.feature.search.search.debounceSearch
import com.artemchep.keyguard.feature.search.search.mapListShape
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun produceSearchSettingsState() = with(localDI().direct) {
    produceSearchSettingsState(
        directDI = this,
        getOrganizations = instance(),
        getCollections = instance(),
    )
}

@Composable
fun produceSearchSettingsState(
    directDI: DirectDI,
    getOrganizations: GetOrganizations,
    getCollections: GetCollections,
): SearchSettingsState = produceScreenState(
    key = "settings_search",
    initial = SearchSettingsState(),
    args = arrayOf(
        getOrganizations,
        getCollections,
    ),
) {
    val queryHandle = textFieldHandle("query")
    val querySink = queryHandle.sink

    val queryFlow = querySink
        .map { it.text }
        .debounceSearch(::identity)
        .map { query ->
            query
                .lowercase()
                .split(" ")
        }
        .shareInScreenScope()

    val e = hub
        .map { (key, component) ->
            val flow = component(directDI)
            flow
                .combine(queryFlow) { item, query ->
                    val tokens = item?.search?.tokens
                        ?: return@combine null
                    val score = findAlike(tokens, query, ignoreCommonWords = false)
                    if (score < 0.1f) {
                        return@combine null
                    }
                    score to item
                }
                .map { pair ->
                    pair ?: return@map null
                    val score = pair.first
                    val item = pair.second
                    val content = item.content
                    if (item.search == null) {
                        return@map null
                    }
                    SearchSettingsState.Item.Settings(
                        key = key,
                        score = score,
                        content = content,
                    )
                }
        }
        .combineToList()
        .map {
            it
                .filterNotNull()
                .sortedByDescending { it.score }
        }
        .map { items ->
            val itemsReShaped = items
                .mapListShape()
            SearchSettingsState(
                items = itemsReShaped,
            )
        }
    combine(
        e,
        querySink,
    ) { a, cell ->
        a.copy(
            query = TextFieldModel(
                text = cell.text,
                textRevision = cell.revision,
                onChange = queryHandle::onChange,
                onSetText = queryHandle::setText,
            ),
        )
    }
}
