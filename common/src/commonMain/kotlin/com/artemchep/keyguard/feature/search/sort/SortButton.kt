package com.artemchep.keyguard.feature.search.sort

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.search.EmptyItem
import com.artemchep.keyguard.feature.search.component.DropdownButton
import com.artemchep.keyguard.feature.search.sort.component.SortItemComposable
import com.artemchep.keyguard.feature.search.sort.component.SortSectionComposable
import com.artemchep.keyguard.feature.search.sort.model.SortItemModel
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun SortButton(
    modifier: Modifier = Modifier,
    items: List<SortItemModel>,
    onClear: (() -> Unit)?,
) {
    DropdownButton(
        modifier = modifier,
        icon = Icons.Outlined.SortByAlpha,
        title = stringResource(Res.strings.sort_header_title),
        items = items,
        onClear = onClear,
    ) {
        SortItems(
            items = items,
        )
    }
}

@Composable
private fun ColumnScope.SortItems(
    items: List<SortItemModel>,
) {
    if (items.isEmpty()) {
        EmptyItem(
            text = stringResource(Res.strings.sort_empty_label),
        )
    }

    items.forEach { item ->
        key(item.id) {
            when (item) {
                is SortItemModel.Item -> SortItemItem(item)
                is SortItemModel.Section -> SortItemSection(item)
            }
        }
    }
}

@Composable
private fun SortItemItem(
    item: SortItemModel.Item,
    modifier: Modifier = Modifier,
) {
    SortItemComposable(
        modifier = modifier,
        checked = item.checked,
        icon = item.icon,
        title = textResource(item.title),
        onClick = item.onClick,
    )
}

@Composable
private fun SortItemSection(
    item: SortItemModel.Section,
    modifier: Modifier = Modifier,
) {
    val text = item.text?.let { textResource(it) }
    SortSectionComposable(
        modifier = modifier,
        text = text,
    )
}
