package com.artemchep.keyguard.feature.tfa.directory

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.twofa.TwoFaServiceInfo
import com.artemchep.keyguard.common.usecase.GetTwoFa
import com.artemchep.keyguard.feature.crashlytics.crashlyticsAttempt
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.keyboard.searchQueryShortcuts
import com.artemchep.keyguard.feature.search.search.searchFilter
import com.artemchep.keyguard.feature.search.search.searchQueryHandle
import com.artemchep.keyguard.feature.servicedirectory.serviceDirectoryItemsFlow
import kotlinx.coroutines.flow.map
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

private class TwoFaServiceListUiException(
    msg: String,
    cause: Throwable,
) : RuntimeException(msg, cause)

@Composable
fun produceTwoFaServiceListState(
) = with(localDI().direct) {
    produceTwoFaServiceListState(
        getTwoFa = instance(),
    )
}

@Composable
fun produceTwoFaServiceListState(
    getTwoFa: GetTwoFa,
): Loadable<TwoFaServiceListState> = produceScreenState(
    key = "tfa_service_list",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    val queryHandle = searchQueryHandle("query")
    searchQueryShortcuts(queryHandle)
    val queryFlow = searchFilter(queryHandle) { model, revision ->
        TwoFaServiceListState.Filter(
            revision = revision,
            query = model,
        )
    }

    fun onClick(model: TwoFaServiceInfo) {
        val route = TwoFaServiceViewFullRoute(
            args = TwoFaServiceViewDialogRoute.Args(
                model = model,
            ),
        )
        val intent = NavigationIntent.Composite(
            listOf(
                NavigationIntent.PopById(TwoFaServicesRoute.ROUTER_NAME),
                NavigationIntent.NavigateToRoute(route),
            ),
        )
        navigate(intent)
    }

    val itemsFlow = serviceDirectoryItemsFlow(
        source = getTwoFa(),
        queryHandle = queryHandle,
        nameOf = TwoFaServiceInfo::name,
        keyOf = TwoFaServiceInfo::name,
        faviconUrlOf = TwoFaServiceInfo::documentation,
        onClick = ::onClick,
        createContentItem = { key, model, name, icon, itemOnClick ->
            TwoFaServiceListState.Item.Content(
                key = key,
                icon = icon,
                name = name,
                data = model,
                onClick = itemOnClick,
            )
        },
        createSectionItem = { key, name ->
            TwoFaServiceListState.Item.Section(
                key = key,
                name = name,
            )
        },
        contentName = { it.name },
        highlightContentItem = { item, name ->
            item.copy(name = name)
        },
        itemKey = { it.key },
        duplicateSectionTag = "TwoFaServiceList",
    )
    val contentFlow = itemsFlow
        .crashlyticsAttempt { e ->
            val msg = "Failed to get the 2fa service list!"
            TwoFaServiceListUiException(
                msg = msg,
                cause = e,
            )
        }
        .map { result ->
            val contentOrException = result
                .map { (items, revision) ->
                    TwoFaServiceListState.Content(
                        revision = revision,
                        items = items,
                    )
                }
            Loadable.Ok(contentOrException)
        }
    contentFlow
        .map { content ->
            val state = TwoFaServiceListState(
                filter = queryFlow,
                content = content,
            )
            Loadable.Ok(state)
        }
}
