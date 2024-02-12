package com.artemchep.keyguard.feature.justdeleteme.directory

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import arrow.core.partially1
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.justdeleteme.JustDeleteMeService
import com.artemchep.keyguard.common.service.justdeleteme.JustDeleteMeServiceInfo
import com.artemchep.keyguard.feature.crashlytics.crashlyticsAttempt
import com.artemchep.keyguard.feature.favicon.FaviconImage
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.artemchep.keyguard.feature.home.vault.search.IndexedText
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
    val queryHandle = searchQueryHandle("query")
    val queryFlow = searchFilter(queryHandle) { model, revision ->
        JustDeleteMeServiceListState.Filter(
            revision = revision,
            query = model,
        )
    }

    val modelComparator = Comparator { a: JustDeleteMeServiceInfo, b: JustDeleteMeServiceInfo ->
        a.name.compareTo(b.name, ignoreCase = true)
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

    fun List<JustDeleteMeServiceInfo>.toItems(): List<JustDeleteMeServiceListState.Item> {
        val nameCollisions = mutableMapOf<String, Int>()
        return this
            .sortedWith(modelComparator)
            .map { serviceInfo ->
                val key = kotlin.run {
                    val newNameCollisionCounter = nameCollisions
                        .getOrDefault(serviceInfo.name, 0) + 1
                    nameCollisions[serviceInfo.name] =
                        newNameCollisionCounter
                    serviceInfo.name + ":" + newNameCollisionCounter
                }
                val faviconUrl = serviceInfo.url?.let { url ->
                    FaviconUrl(
                        serverId = null,
                        url = url,
                    )
                }
                JustDeleteMeServiceListState.Item(
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
                    data = serviceInfo,
                    onClick = ::onClick
                        .partially1(serviceInfo),
                )
            }
    }

    val itemsFlow = justDeleteMeService.get()
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
    contentFlow
        .map { content ->
            val state = JustDeleteMeServiceListState(
                filter = queryFlow,
                content = content,
            )
            Loadable.Ok(state)
        }
}
