package com.artemchep.keyguard.feature.search.filter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.artemchep.keyguard.feature.search.EmptyItem
import com.artemchep.keyguard.feature.search.filter.component.FilterItemComposable
import com.artemchep.keyguard.feature.search.filter.component.FilterSectionComposable
import com.artemchep.keyguard.feature.search.filter.model.FilterItemModel
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.compose.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColumnScope.FilterItems(
    items: List<FilterItemModel>,
    predicate: (FilterItemModel) -> Boolean = { true },
) {
    if (items.isEmpty()) {
        EmptyItem(
            text = stringResource(Res.strings.filter_empty_label),
        )
    }

    FlowRow(
        modifier = Modifier
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            if (!predicate(item)) {
                return@forEach
            }
            key(item.id) {
                when (item) {
                    is FilterItemModel.Item -> FilterItemItem(item)
                    is FilterItemModel.Section -> FilterItemSection(item)
                }
            }
        }
    }
}

@Composable
private fun FilterItemItem(
    item: FilterItemModel.Item,
) {
    val indent = item.indent * 16.dp
    FilterItemComposable(
        modifier = Modifier
            .padding(start = indent)
            .then(
                if (item.fill) {
                    Modifier
                        .fillMaxWidth()
                } else {
                    Modifier
                },
            ),
        checked = item.checked,
        leading = item.leading,
        title = item.title,
        text = item.text,
        onClick = item.onClick,
    )
}

@Composable
private fun FilterItemSection(
    item: FilterItemModel.Section,
) {
    FilterSectionComposable(
        expanded = item.expanded,
        title = item.text,
        onClick = item.onClick,
    )
}
