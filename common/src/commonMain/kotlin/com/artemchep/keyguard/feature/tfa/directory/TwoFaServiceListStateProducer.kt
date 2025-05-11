package com.artemchep.keyguard.feature.tfa.directory

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import arrow.core.partially1
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.twofa.TwoFaService
import com.artemchep.keyguard.common.service.twofa.TwoFaServiceInfo
import com.artemchep.keyguard.common.usecase.GetTwoFa
import com.artemchep.keyguard.feature.crashlytics.crashlyticsAttempt
import com.artemchep.keyguard.feature.favicon.FaviconImage
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.artemchep.keyguard.feature.home.vault.search.IndexedText
import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.keyboard.searchQueryShortcuts
import com.artemchep.keyguard.feature.search.search.IndexedModel
import com.artemchep.keyguard.feature.search.search.mapSearch
import com.artemchep.keyguard.feature.search.search.searchFilter
import com.artemchep.keyguard.feature.search.search.searchQueryHandle
import kotlinx.coroutines.flow.asFlow
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

    val modelComparator = Comparator { a: TwoFaServiceInfo, b: TwoFaServiceInfo ->
        AlphabeticalSort.compareStr(a.name, b.name)
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

    fun List<TwoFaServiceInfo>.toItems(): List<TwoFaServiceListState.Item> {
        val nameCollisions = mutableMapOf<String, Int>()
        return this
            .sortedWith(modelComparator)
            .map { appInfo ->
                val key = kotlin.run {
                    val newPackageNameCollisionCounter = nameCollisions
                        .getOrDefault(appInfo.name, 0) + 1
                    nameCollisions[appInfo.name] =
                        newPackageNameCollisionCounter
                    appInfo.name + ":" + newPackageNameCollisionCounter
                }
                val faviconUrl = appInfo.documentation?.let { url ->
                    FaviconUrl(
                        serverId = null,
                        url = url,
                    )
                }
                TwoFaServiceListState.Item(
                    key = key,
                    icon = {
                        FaviconImage(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape),
                            imageModel = { faviconUrl },
                        )
                    },
                    name = AnnotatedString(appInfo.name),
                    data = appInfo,
                    onClick = ::onClick
                        .partially1(appInfo),
                )
            }
    }

    val itemsFlow = getTwoFa()
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
            val msg = "Failed to get the just-delete-me list!"
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
