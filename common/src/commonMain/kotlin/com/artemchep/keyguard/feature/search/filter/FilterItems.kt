package com.artemchep.keyguard.feature.search.filter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.search.EmptyItem
import com.artemchep.keyguard.feature.search.filter.component.FilterListItemComposable
import com.artemchep.keyguard.feature.search.filter.component.FilterChipItemComposable
import com.artemchep.keyguard.feature.search.filter.component.FilterSectionComposable
import com.artemchep.keyguard.feature.search.filter.model.FilterItemModel
import com.artemchep.keyguard.feature.search.filter.model.FilterItemModel.Section.Layout
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.stringResource

/**
 * The [endEdge] is where focus goes from the items at the end edge,
 * such as the last focusable item of each line of chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColumnScope.FilterItems(
    items: List<FilterItemModel>,
    predicate: (FilterItemModel) -> Boolean = { true },
    endEdge: FocusRequester = FocusRequester.Default,
) {
    if (items.isEmpty()) {
        EmptyItem(
            text = stringResource(Res.string.filter_empty_label),
        )
        return
    }

    val visibleItems = items
        .filter(predicate)
    if (visibleItems.isEmpty()) {
        return
    }

    val blocks = visibleItems
        .toFilterItemsBlocks()
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block ->
            // A block that appears above keeps
            // the focused item of this one.
            key(block.key) {
                when (block) {
                    is FilterItemsBlock.Flow -> FilterItemsFlowBlock(
                        items = block.items,
                        endEdge = endEdge,
                    )

                    is FilterItemsBlock.ListSection -> FilterItemsListBlock(
                        section = block.section,
                        items = block.items,
                        endEdge = endEdge,
                    )
                }
            }
        }
    }
}

private sealed interface FilterItemsBlock {
    val key: String

    data class Flow(
        val items: List<FilterItemModel>,
    ) : FilterItemsBlock {
        // Usually the id of its first section.
        override val key: String get() = items.first().id
    }

    data class ListSection(
        val section: FilterItemModel.Section,
        val items: List<FilterItemModel>,
    ) : FilterItemsBlock {
        override val key: String get() = section.id
    }
}

private fun List<FilterItemModel>.toFilterItemsBlocks(): List<FilterItemsBlock> {
    val blocks = mutableListOf<FilterItemsBlock>()
    val flowItems = mutableListOf<FilterItemModel>()
    var listSection: FilterItemModel.Section? = null
    val listItems = mutableListOf<FilterItemModel>()

    fun flushFlowItems() {
        if (flowItems.isEmpty()) {
            return
        }

        blocks += FilterItemsBlock.Flow(
            items = flowItems.toList(),
        )
        flowItems.clear()
    }

    fun flushListItems() {
        val section = listSection
            ?: return
        blocks += FilterItemsBlock.ListSection(
            section = section,
            items = listItems.toList(),
        )
        listSection = null
        listItems.clear()
    }

    forEach { item ->
        when (item) {
            is FilterItemModel.Section -> {
                when (item.layout) {
                    FilterItemModel.Section.Layout.Flow -> {
                        flushListItems()
                        flowItems += item
                    }

                    FilterItemModel.Section.Layout.List -> {
                        flushFlowItems()
                        flushListItems()
                        listSection = item
                    }
                }
            }

            is FilterItemModel.ChipItem -> {
                if (listSection != null) {
                    listItems += item
                } else {
                    flowItems += item
                }
            }

            is FilterItemModel.ListItem -> {
                if (listSection != null) {
                    listItems += item
                } else {
                    flowItems += item
                }
            }
        }
    }

    flushFlowItems()
    flushListItems()
    return blocks
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterItemsFlowBlock(
    items: List<FilterItemModel>,
    endEdge: FocusRequester,
) {
    val lineEnds = remember(endEdge) {
        FlowLineEnds(endEdge)
            .takeIf { endEdge != FocusRequester.Default }
    }
    if (lineEnds != null) {
        SideEffect {
            lineEnds.items = items
        }
    }
    FlowRow(
        modifier = Modifier
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            key(item.id) {
                val itemModifier = if (lineEnds != null) {
                    remember(lineEnds) {
                        lineEnds.modifier(item.id)
                    }
                } else {
                    Modifier
                }
                when (item) {
                    is FilterItemModel.ChipItem -> FilterItemItem(
                        modifier = itemModifier,
                        item = item,
                    )

                    is FilterItemModel.ListItem -> FilterNestedFolderItem(
                        modifier = itemModifier,
                        item = item,
                    )

                    is FilterItemModel.Section -> FilterItemSection(
                        modifier = itemModifier,
                        item = item,
                    )
                }
            }
        }
    }
}

/**
 * Finds the last focusable item of each line of a flow block when focus
 * searches, from where the items were placed. So nothing recomposes when
 * an item moves to another line.
 */
private class FlowLineEnds(
    private val endEdge: FocusRequester,
) {
    private val coordinates = mutableMapOf<String, LayoutCoordinates>()

    /** The index of the first item with an id. */
    private var indexById: Map<String, Int> = emptyMap()

    /** For each index, the index of the next item that can take focus, or -1. */
    private var nextFocusable = IntArray(0)

    var items: List<FilterItemModel> = emptyList()
        set(value) {
            field = value
            val indices = HashMap<String, Int>(value.size)
            value.forEachIndexed { index, item -> indices.getOrPut(item.id) { index } }
            indexById = indices
            // Focus skips the items that can not take it.
            val next = IntArray(value.size)
            var nextIndex = -1
            for (index in value.indices.reversed()) {
                next[index] = nextIndex
                if (value[index].canFocus()) nextIndex = index
            }
            nextFocusable = next
            coordinates.keys.retainAll(indices.keys)
        }

    fun modifier(id: String): Modifier = Modifier
        .onPlaced { coordinates[id] = it }
        .focusProperties {
            if (isLineEnd(id)) end = endEdge
        }

    private fun isLineEnd(id: String): Boolean {
        val index = indexById[id]
        val bounds = placed(id)
        if (index == null || bounds == null) return false
        val next = items.getOrNull(nextFocusable[index])
        val nextTop = next?.let { placed(it.id) }?.positionInRoot()?.y
        // A line is as tall as its tallest item, so
        // the next line starts below this item.
        val bottom = bounds.positionInRoot().y + bounds.size.height
        return next == null || nextTop != null && nextTop >= bottom
    }

    private fun placed(id: String) = coordinates[id]?.takeIf { it.isAttached }
}

private fun FilterItemModel.canFocus(): Boolean = when (this) {
    is FilterItemModel.ChipItem -> enabled
    is FilterItemModel.ListItem -> enabled
    is FilterItemModel.Section -> onClick != null
    else -> false
}

@Composable
private fun FilterItemsListBlock(
    section: FilterItemModel.Section,
    items: List<FilterItemModel>,
    endEdge: FocusRequester,
) {
    val expandedNodeIds = remember(section.id) {
        mutableStateMapOf<String, Boolean>()
    }
    val nestedFolders = remember(items) {
        items
            .filterIsInstance<FilterItemModel.ListItem>()
            .associateBy { item -> item.nodeId }
    }

    fun isVisible(item: FilterItemModel.ListItem): Boolean {
        var parentId: String? = item.parentNodeId
            ?: return true
        val visited = mutableSetOf<String>()
        while (parentId != null && visited.add(parentId)) {
            if (expandedNodeIds[parentId] != true) {
                return false
            }

            parentId = nestedFolders[parentId]
                ?.parentNodeId
        }
        return true
    }

    // Every item is at the end edge.
    val endModifier = rememberEndEdgeModifier(endEdge)
    val isList = section.layout == Layout.List
    Column(
        modifier = Modifier
            .padding(horizontal = 8.dp),
        verticalArrangement = if (isList) {
            Arrangement.spacedBy(3.dp)
        } else Arrangement.spacedBy(8.dp),
    ) {
        key(section.id) {
            FilterItemSection(
                modifier = endModifier
                    .fillMaxWidth(),
                item = section,
            )
        }
        items.forEach { item ->
            key(item.id) {
                when (item) {
                    is FilterItemModel.ChipItem -> {
                        FilterItemItem(
                            modifier = endModifier
                                .fillMaxWidth(),
                            item = item,
                        )
                    }

                    is FilterItemModel.ListItem -> {
                        if (isVisible(item)) {
                            val expanded = expandedNodeIds[item.nodeId] == true
                            FilterNestedFolderItem(
                                modifier = Modifier
                                    .fillMaxWidth(),
                                expanded = expanded,
                                onExpandedChange = if (item.expandable) {
                                    { shouldExpand ->
                                        expandedNodeIds[item.nodeId] = shouldExpand
                                    }
                                } else {
                                    null
                                },
                                endEdge = endEdge,
                                item = item,
                            )
                        }
                    }

                    is FilterItemModel.Section -> Unit
                }
            }
        }
    }
}

/** Points the end key of an item at the [endEdge]. */
@Composable
private fun rememberEndEdgeModifier(endEdge: FocusRequester): Modifier = remember(endEdge) {
    if (endEdge != FocusRequester.Default) {
        Modifier.focusProperties { end = endEdge }
    } else {
        Modifier
    }
}

@Composable
private fun FilterItemItem(
    modifier: Modifier = Modifier,
    item: FilterItemModel.ChipItem,
) {
    FilterChipItemComposable(
        modifier = modifier,
        checked = item.checked,
        enabled = item.enabled,
        leading = item.leading,
        title = item.title,
        text = item.text,
        textMaxLines = item.textMaxLines,
        onClick = item.onClick,
    )
}

@Composable
private fun FilterNestedFolderItem(
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    endEdge: FocusRequester = FocusRequester.Default,
    item: FilterItemModel.ListItem,
) {
    val onClick = item.onClick
        ?: onExpandedChange
            ?.let { onChange ->
                {
                    onChange(!expanded)
                }
            }
    FilterListItemComposable(
        modifier = modifier
            .fillMaxWidth(),
        checked = item.checked,
        enabled = item.enabled || onExpandedChange != null,
        leading = item.leading,
        title = item.title,
        text = item.text,
        textMaxLines = item.textMaxLines,
        depth = item.depth,
        expanded = expanded.takeIf { item.expandable },
        onToggle = onExpandedChange,
        onClick = onClick,
        endEdge = endEdge,
    )
}

@Composable
private fun FilterItemSection(
    modifier: Modifier = Modifier,
    item: FilterItemModel.Section,
) {
    FilterSectionComposable(
        modifier = modifier,
        expandable = item.expandable,
        expanded = item.expanded,
        title = item.text,
        onClick = item.onClick,
    )
}
