package com.artemchep.keyguard.feature.servicedirectory

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.feature.decorator.ItemDecorator
import com.artemchep.keyguard.feature.decorator.ItemDecoratorNone
import com.artemchep.keyguard.feature.decorator.ItemDecoratorTitle
import com.artemchep.keyguard.feature.decorator.forEachWithDecorUniqueSectionsOnly
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.artemchep.keyguard.feature.home.vault.search.IndexedText
import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import com.artemchep.keyguard.feature.home.vault.util.AlphabeticalSortMinItemsSize
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.search.search.IndexedModel
import com.artemchep.keyguard.feature.search.search.SearchQueryHandle
import com.artemchep.keyguard.feature.search.search.mapSearch
import com.artemchep.keyguard.feature.search.search.mapShape
import com.artemchep.keyguard.ui.icons.FaviconIcon
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map

internal fun <Model, Item : Any, ContentItem : Item, SectionItem : Item> RememberStateFlowScope.serviceDirectoryItemsFlow(
    source: IO<List<Model>>,
    queryHandle: SearchQueryHandle,
    nameOf: (Model) -> String,
    keyOf: (Model) -> String,
    faviconUrlOf: (Model) -> String?,
    onClick: (Model) -> Unit,
    createContentItem: (
        key: String,
        model: Model,
        name: AnnotatedString,
        icon: @Composable () -> Unit,
        onClick: () -> Unit,
    ) -> ContentItem,
    createSectionItem: (key: String, name: String) -> SectionItem,
    contentName: (ContentItem) -> AnnotatedString,
    highlightContentItem: (ContentItem, AnnotatedString) -> ContentItem,
    itemKey: (Item) -> String,
    duplicateSectionTag: String,
): Flow<Pair<List<Item>, Int>> {
    val modelComparator = Comparator { a: Model, b: Model ->
        AlphabeticalSort.compareStr(nameOf(a), nameOf(b))
    }

    fun List<Model>.toItems(): List<ContentItem> {
        val keyCollisions = mutableMapOf<String, Int>()
        return this
            .sortedWith(modelComparator)
            .map { model ->
                val keyPrefix = keyOf(model)
                val keyCollisionCounter = keyCollisions
                    .getOrElse(keyPrefix) { 0 } + 1
                keyCollisions[keyPrefix] = keyCollisionCounter

                val faviconUrl = faviconUrlOf(model)?.let { url ->
                    FaviconUrl(
                        serverId = null,
                        url = url,
                    )
                }
                createContentItem(
                    "$keyPrefix:$keyCollisionCounter",
                    model,
                    AnnotatedString(nameOf(model)),
                    {
                        ServiceDirectoryFaviconIcon(
                            faviconUrl = faviconUrl,
                        )
                    },
                    {
                        onClick(model)
                    },
                )
            }
    }

    return source
        .asFlow()
        .map { models ->
            models
                .toItems()
                .map { item ->
                    IndexedModel(
                        model = item,
                        indexedText = IndexedText.invoke(contentName(item).text),
                    )
                }
        }
        .mapSearch(
            handle = queryHandle,
        ) { item, result ->
            highlightContentItem(item, result.highlightedText)
        }
        .map { (items, rev) ->
            @Suppress("UNCHECKED_CAST")
            val decorator: ItemDecorator<Item, ContentItem> =
                when {
                    items.size >= AlphabeticalSortMinItemsSize ->
                        ItemDecoratorTitle(
                            selector = { contentName(it).text },
                            factory = createSectionItem,
                        )

                    else ->
                        ItemDecoratorNone as ItemDecorator<Item, ContentItem>
                }

            val out = mutableListOf<Item>()
            items.forEachWithDecorUniqueSectionsOnly(
                decorator = decorator,
                tag = duplicateSectionTag,
                provideItemId = itemKey,
            ) { item ->
                out += item
            }
            out to rev
        }
        .mapShape()
}

@Composable
private fun ServiceDirectoryFaviconIcon(
    faviconUrl: FaviconUrl?,
) {
    FaviconIcon(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape),
        imageModel = { faviconUrl },
    )
}
