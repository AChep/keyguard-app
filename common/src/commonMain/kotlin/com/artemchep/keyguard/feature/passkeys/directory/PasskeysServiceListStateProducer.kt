package com.artemchep.keyguard.feature.passkeys.directory

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import arrow.core.partially1
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.passkey.PassKeyService
import com.artemchep.keyguard.common.service.passkey.PassKeyServiceInfo
import com.artemchep.keyguard.common.usecase.GetPasskeys
import com.artemchep.keyguard.feature.crashlytics.crashlyticsAttempt
import com.artemchep.keyguard.feature.favicon.FaviconImage
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.artemchep.keyguard.feature.home.vault.search.IndexedText
import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.search.IndexedModel
import com.artemchep.keyguard.feature.search.search.mapSearch
import com.artemchep.keyguard.feature.search.search.searchFilter
import com.artemchep.keyguard.feature.search.search.searchQueryHandle
import kotlinx.coroutines.flow.asFlow
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
    val queryHandle = searchQueryHandle("query")
    val queryFlow = searchFilter(queryHandle) { model, revision ->
        PasskeysServiceListState.Filter(
            revision = revision,
            query = model,
        )
    }

    val modelComparator = Comparator { a: PassKeyServiceInfo, b: PassKeyServiceInfo ->
        AlphabeticalSort.compareStr(a.name, b.name)
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

    fun List<PassKeyServiceInfo>.toItems(): List<PasskeysServiceListState.Item> {
        val nameCollisions = mutableMapOf<String, Int>()
        return this
            .sortedWith(modelComparator)
            .map { serviceInfo ->
                val key = kotlin.run {
                    val newNameCollisionCounter = nameCollisions
                        .getOrDefault(serviceInfo.id, 0) + 1
                    nameCollisions[serviceInfo.id] =
                        newNameCollisionCounter
                    serviceInfo.id + ":" + newNameCollisionCounter
                }
                val faviconUrl = serviceInfo.documentation?.let { url ->
                    FaviconUrl(
                        serverId = null,
                        url = url,
                    )
                }
                PasskeysServiceListState.Item(
                    key = key,
                    icon = {
                        FaviconImage(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape),
                            imageModel = { faviconUrl },
                        )
                    },
                    name = AnnotatedString(serviceInfo.name),
                    text = serviceInfo.domain,
                    data = serviceInfo,
                    onClick = ::onClick
                        .partially1(serviceInfo),
                )
            }
    }

    val itemsFlow = getPasskeys()
        .asFlow()
        .map { apps ->
            apps
                .toItems()
                // Index for the search.
                .map { item ->
                    IndexedModel(
                        model = item,
                        indexedText = IndexedText.invoke(item.name.text),
                    )
                }
        }
        .mapSearch(
            handle = queryHandle,
        ) { item, result ->
            // Replace the origin text with the one with
            // search decor applied to it.
            item.copy(name = result.highlightedText)
        }
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
    contentFlow
        .map { content ->
            val state = PasskeysServiceListState(
                filter = queryFlow,
                content = content,
            )
            Loadable.Ok(state)
        }
}
