package com.artemchep.keyguard.feature.home.settings.search

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.util.flow.combineToList
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.home.settings.hub
import com.artemchep.keyguard.feature.home.vault.search.findAlike
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.search.SEARCH_DEBOUNCE
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
    val querySink = mutablePersistedFlow("query") { "" }
    val queryState = mutableComposeState(querySink)

    val queryFlow = querySink
        .debounce(SEARCH_DEBOUNCE)
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
            SearchSettingsState(
                items = items,
            )
        }
    combine(
        e,
        querySink,
    ) { a, b ->
        a.copy(
            query = TextFieldModel2(
                state = queryState,
                text = b,
                onChange = queryState::value::set,
            ),
        )
    }
}
