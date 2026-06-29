package com.artemchep.keyguard.feature.search.filter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColumnScope.FilterItems(
    items: List<FilterItemModel>,
    predicate: (FilterItemModel) -> Boolean = { true },
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
            when (block) {
                is FilterItemsBlock.Flow -> FilterItemsFlowBlock(block.items)
                is FilterItemsBlock.ListSection -> FilterItemsListBlock(
                    section = block.section,
                    items = block.items,
                )
            }
        }
    }
}

private sealed interface FilterItemsBlock {
    data class Flow(
        val items: List<FilterItemModel>,
    ) : FilterItemsBlock

    data class ListSection(
        val section: FilterItemModel.Section,
        val items: List<FilterItemModel>,
    ) : FilterItemsBlock
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
) {
    FlowRow(
        modifier = Modifier
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            key(item.id) {
                when (item) {
                    is FilterItemModel.ChipItem -> FilterItemItem(
                        item = item,
                    )

                    is FilterItemModel.ListItem -> FilterNestedFolderItem(
                        item = item,
                    )

                    is FilterItemModel.Section -> FilterItemSection(
                        item = item,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterItemsListBlock(
    section: FilterItemModel.Section,
    items: List<FilterItemModel>,
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
                modifier = Modifier
                    .fillMaxWidth(),
                item = section,
            )
        }
        items.forEach { item ->
            key(item.id) {
                when (item) {
                    is FilterItemModel.ChipItem -> {
                        FilterItemItem(
                            modifier = Modifier
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
