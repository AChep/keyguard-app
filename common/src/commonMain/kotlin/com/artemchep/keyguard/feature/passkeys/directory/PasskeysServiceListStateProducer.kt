package com.artemchep.keyguard.feature.passkeys.directory

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.passkey.PassKeyServiceInfo
import com.artemchep.keyguard.common.usecase.GetPasskeys
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

private class PasskeysServiceListUiException(
    msg: String,
    cause: Throwable,
) : RuntimeException(msg, cause)

@Composable
fun producePasskeysListState(
) = with(localDI().direct) {
    producePasskeysListState(
        getPasskeys = instance(),
    )
}

@Composable
fun producePasskeysListState(
    getPasskeys: GetPasskeys,
): Loadable<PasskeysServiceListState> = produceScreenState(
    key = "passkeys_service_list",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    passkeysServiceListStateProducer(
        getPasskeys = getPasskeys,
    )
}

suspend fun RememberStateFlowScope.passkeysServiceListStateProducer(
    getPasskeys: GetPasskeys,
): Flow<Loadable<PasskeysServiceListState>> {
    val queryHandle = searchQueryHandle("query")
    searchQueryShortcuts(queryHandle)
    val queryFlow = searchFilter(queryHandle) { model, revision ->
        PasskeysServiceListState.Filter(
            revision = revision,
            query = model,
        )
    }

    fun onClick(model: PassKeyServiceInfo) {
        val route = PasskeysServiceViewFullRoute(
            args = PasskeysServiceViewDialogRoute.Args(
                model = model,
            ),
        )
        val intent = NavigationIntent.Composite(
            listOf(
                NavigationIntent.PopById(PasskeysServicesRoute.ROUTER_NAME),
                NavigationIntent.NavigateToRoute(route),
            ),
        )
        navigate(intent)
    }

    val itemsFlow = serviceDirectoryItemsFlow(
        source = getPasskeys(),
        queryHandle = queryHandle,
        nameOf = PassKeyServiceInfo::name,
        keyOf = PassKeyServiceInfo::id,
        faviconUrlOf = PassKeyServiceInfo::documentation,
        onClick = ::onClick,
        createContentItem = { key, model, name, icon, itemOnClick ->
            PasskeysServiceListState.Item.Content(
                key = key,
                icon = icon,
                name = name,
                text = model.domain,
                data = model,
                onClick = itemOnClick,
            )
        },
        createSectionItem = { key, name ->
            PasskeysServiceListState.Item.Section(
                key = key,
                name = name,
            )
        },
        contentName = { it.name },
        highlightContentItem = { item, name ->
            item.copy(name = name)
        },
        itemKey = { it.key },
        duplicateSectionTag = "PasskeysServiceList",
    )
    val contentFlow = itemsFlow
        .crashlyticsAttempt { e ->
            val msg = "Failed to get the passkeys list!"
            PasskeysServiceListUiException(
                msg = msg,
                cause = e,
            )
        }
        .map { result ->
            val contentOrException = result
                .map { (items, revision) ->
                    PasskeysServiceListState.Content(
                        revision = revision,
                        items = items,
                    )
                }
            Loadable.Ok(contentOrException)
        }
    return contentFlow
        .map { content ->
            val state = PasskeysServiceListState(
                filter = queryFlow,
                content = content,
            )
            Loadable.Ok(state)
        }
}
