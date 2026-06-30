package com.artemchep.keyguard.feature.justdeleteme.directory

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.justdeleteme.JustDeleteMeService
import com.artemchep.keyguard.common.service.justdeleteme.JustDeleteMeServiceInfo
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

private class JustDeleteMeServiceListUiException(
    msg: String,
    cause: Throwable,
) : RuntimeException(msg, cause)

@Composable
fun produceJustDeleteMeServiceListState(
) = with(localDI().direct) {
    produceJustDeleteMeServiceListState(
        justDeleteMeService = instance(),
    )
}

@Composable
fun produceJustDeleteMeServiceListState(
    justDeleteMeService: JustDeleteMeService,
): Loadable<JustDeleteMeServiceListState> = produceScreenState(
    key = "justdeleteme_service_list",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    justDeleteMeServiceListStateProducer(
        justDeleteMeService = justDeleteMeService,
    )
}

suspend fun RememberStateFlowScope.justDeleteMeServiceListStateProducer(
    justDeleteMeService: JustDeleteMeService,
): Flow<Loadable<JustDeleteMeServiceListState>> {
    val queryHandle = searchQueryHandle("query")
    searchQueryShortcuts(queryHandle)
    val queryFlow = searchFilter(queryHandle) { model, revision ->
        JustDeleteMeServiceListState.Filter(
            revision = revision,
            query = model,
        )
    }

    fun onClick(model: JustDeleteMeServiceInfo) {
        val route = JustDeleteMeServiceViewFullRoute(
            args = JustDeleteMeServiceViewDialogRoute.Args(
                justDeleteMe = model,
            ),
        )
        val intent = NavigationIntent.Composite(
            listOf(
                NavigationIntent.PopById(JustDeleteMeServicesRoute.ROUTER_NAME),
                NavigationIntent.NavigateToRoute(route),
            ),
        )
        navigate(intent)
    }

    val itemsFlow = serviceDirectoryItemsFlow(
        source = justDeleteMeService.get(),
        queryHandle = queryHandle,
        nameOf = JustDeleteMeServiceInfo::name,
        keyOf = JustDeleteMeServiceInfo::name,
        faviconUrlOf = JustDeleteMeServiceInfo::url,
        onClick = ::onClick,
        createContentItem = { key, model, name, icon, itemOnClick ->
            JustDeleteMeServiceListState.Item.Content(
                key = key,
                icon = icon,
                name = name,
                data = model,
                onClick = itemOnClick,
            )
        },
        createSectionItem = { key, name ->
            JustDeleteMeServiceListState.Item.Section(
                key = key,
                name = name,
            )
        },
        contentName = { it.name },
        highlightContentItem = { item, name ->
            item.copy(name = name)
        },
        itemKey = { it.key },
        duplicateSectionTag = "JustDeleteMeList",
    )
    val contentFlow = itemsFlow
        .crashlyticsAttempt { e ->
            val msg = "Failed to get the just-delete-me list!"
            JustDeleteMeServiceListUiException(
                msg = msg,
                cause = e,
            )
        }
        .map { result ->
            val contentOrException = result
                .map { (items, revision) ->
                    JustDeleteMeServiceListState.Content(
                        revision = revision,
                        items = items,
                    )
                }
            Loadable.Ok(contentOrException)
        }
    return contentFlow
        .map { content ->
            val state = JustDeleteMeServiceListState(
                filter = queryFlow,
                content = content,
            )
            Loadable.Ok(state)
        }
}
