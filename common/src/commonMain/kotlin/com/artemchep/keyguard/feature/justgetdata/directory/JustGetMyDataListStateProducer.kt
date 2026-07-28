package com.artemchep.keyguard.feature.justgetdata.directory

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.justgetmydata.JustGetMyDataService
import com.artemchep.keyguard.common.service.justgetmydata.JustGetMyDataServiceInfo
import com.artemchep.keyguard.feature.crashlytics.crashlyticsAttempt
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.keyboard.searchQueryShortcuts
import com.artemchep.keyguard.feature.search.search.searchFilter
import com.artemchep.keyguard.feature.search.search.searchQueryHandle
import com.artemchep.keyguard.feature.servicedirectory.serviceDirectoryItemsFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

private class JustGetMyDataListUiException(
    msg: String,
    cause: Throwable,
) : RuntimeException(msg, cause)

@Composable
fun produceJustGetMyDataListState(
) = with(localDI().direct) {
    produceJustGetMyDataListState(
        justGetMyDataService = instance(),
    )
}

@Composable
fun produceJustGetMyDataListState(
    justGetMyDataService: JustGetMyDataService,
): Loadable<JustGetMyDataListState> = produceScreenState(
    key = "justgetmydata_list",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    justGetMyDataListStateProducer(
        justGetMyDataService = justGetMyDataService,
    )
}

suspend fun RememberStateFlowScope.justGetMyDataListStateProducer(
    justGetMyDataService: JustGetMyDataService,
): Flow<Loadable<JustGetMyDataListState>> {
    val queryHandle = searchQueryHandle("query")
    searchQueryShortcuts(queryHandle)
    val queryFlow = searchFilter(queryHandle) { model, revision ->
        JustGetMyDataListState.Filter(
            revision = revision,
            query = model,
        )
    }

    fun onClick(model: JustGetMyDataServiceInfo) {
        val route = JustGetMyDataViewFullRoute(
            args = JustGetMyDataViewDialogRoute.Args(
                model = model,
            ),
        )
        val intent = NavigationIntent.Composite(
            listOf(
                NavigationIntent.PopById(JustGetMyDataServicesRoute.ROUTER_NAME),
                NavigationIntent.NavigateToRoute(route),
            ),
        )
        navigate(intent)
    }

    val itemsFlow = serviceDirectoryItemsFlow(
        source = justGetMyDataService.get(),
        queryHandle = queryHandle,
        nameOf = JustGetMyDataServiceInfo::name,
        keyOf = JustGetMyDataServiceInfo::name,
        faviconUrlOf = JustGetMyDataServiceInfo::url,
        onClick = ::onClick,
        createContentItem = { key, model, name, icon, itemOnClick ->
            JustGetMyDataListState.Item.Content(
                key = key,
                icon = icon,
                name = name,
                data = model,
                onClick = itemOnClick,
            )
        },
        createSectionItem = { key, name ->
            JustGetMyDataListState.Item.Section(
                key = key,
                name = name,
            )
        },
        contentName = { it.name },
        highlightContentItem = { item, name ->
            item.copy(name = name)
        },
        itemKey = { it.key },
        duplicateSectionTag = "JustGetMyDataList",
    )
    val contentFlow = itemsFlow
        .crashlyticsAttempt { e ->
            val msg = "Failed to get the just-get-my-data list!"
            JustGetMyDataListUiException(
                msg = msg,
                cause = e,
            )
        }
        .map { result ->
            val contentOrException = result
                .map { (items, revision) ->
                    JustGetMyDataListState.Content(
                        revision = revision,
                        items = items,
                    )
                }
            Loadable.Ok(contentOrException)
        }
    return contentFlow
        .map { content ->
            val state = JustGetMyDataListState(
                filter = queryFlow,
                content = content,
            )
            Loadable.Ok(state)
        }
}
