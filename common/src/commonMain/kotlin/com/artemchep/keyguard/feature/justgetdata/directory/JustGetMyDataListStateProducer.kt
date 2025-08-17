package com.artemchep.keyguard.feature.justgetdata.directory

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import arrow.core.partially1
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.service.justgetmydata.JustGetMyDataService
import com.artemchep.keyguard.common.service.justgetmydata.JustGetMyDataServiceInfo
import com.artemchep.keyguard.feature.crashlytics.crashlyticsAttempt
import com.artemchep.keyguard.feature.decorator.ItemDecorator
import com.artemchep.keyguard.feature.decorator.ItemDecoratorNone
import com.artemchep.keyguard.feature.decorator.ItemDecoratorTitle
import com.artemchep.keyguard.feature.favicon.FaviconImage
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.artemchep.keyguard.feature.home.vault.search.IndexedText
import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import com.artemchep.keyguard.feature.home.vault.util.AlphabeticalSortMinItemsSize
import com.artemchep.keyguard.feature.justdeleteme.directory.JustDeleteMeServiceListState
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.keyboard.searchQueryShortcuts
import com.artemchep.keyguard.feature.search.search.IndexedModel
import com.artemchep.keyguard.feature.search.search.mapSearch
import com.artemchep.keyguard.feature.search.search.mapShape
import com.artemchep.keyguard.feature.search.search.searchFilter
import com.artemchep.keyguard.feature.search.search.searchQueryHandle
import com.artemchep.keyguard.platform.recordException
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
    val queryHandle = searchQueryHandle("query")
    searchQueryShortcuts(queryHandle)
    val queryFlow = searchFilter(queryHandle) { model, revision ->
        JustGetMyDataListState.Filter(
            revision = revision,
            query = model,
        )
    }

    val modelComparator = Comparator { a: JustGetMyDataServiceInfo, b: JustGetMyDataServiceInfo ->
        AlphabeticalSort.compareStr(a.name, b.name)
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

    fun List<JustGetMyDataServiceInfo>.toItems(): List<JustGetMyDataListState.Item.Content> {
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
                JustGetMyDataListState.Item.Content(
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

    val itemsFlow = justGetMyDataService.get()
        .asFlow()
        .map { data ->
            data
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
        .map { (items, rev) ->
            val decorator: ItemDecorator<JustGetMyDataListState.Item, JustGetMyDataListState.Item.Content> =
                when {
                    items.size >= AlphabeticalSortMinItemsSize ->
                        ItemDecoratorTitle(
                            selector = { it.name.text },
                            factory = { id, text ->
                                JustGetMyDataListState.Item.Section(
                                    key = id,
                                    name = text,
                                )
                            },
                        )

                    else ->
                        ItemDecoratorNone
                                as ItemDecorator<JustGetMyDataListState.Item, JustGetMyDataListState.Item.Content>
                }

            val sectionIds = mutableSetOf<String>()
            val out = mutableListOf<JustGetMyDataListState.Item>()
            items.forEach { item ->
                val section = decorator.getOrNull(item)
                // Some weird combinations of items might lead to
                // duplicate # being used.
                if (section != null) {
                    if (section.key !in sectionIds) {
                        sectionIds += section.key
                        out += section
                    } else {
                        val sections = sectionIds
                            .joinToString()

                        val msg =
                            "Duplicate sections prevented @ JustDeleteMeList: $sections, [${section.key}]"
                        val exception = RuntimeException(msg)
                        recordException(exception)
                    }
                }

                out += item
            }
            out to rev
        }
        .mapShape()
    val contentFlow = itemsFlow
        .crashlyticsAttempt { e ->
            val msg = "Failed to get the just-get-my-data list!"
            JustDeleteMeServiceListUiException(
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
    contentFlow
        .map { content ->
            val state = JustGetMyDataListState(
                filter = queryFlow,
                content = content,
            )
            Loadable.Ok(state)
        }
}
