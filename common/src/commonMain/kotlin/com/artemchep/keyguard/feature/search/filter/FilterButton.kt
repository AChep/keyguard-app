package com.artemchep.keyguard.feature.search.filter

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.feature.search.component.DropdownButton
import com.artemchep.keyguard.feature.search.filter.component.VaultHomeScreenFilterPaneNumberOfItems
import com.artemchep.keyguard.feature.search.filter.model.FilterItemModel
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun FilterButton(
    modifier: Modifier = Modifier,
    count: Int?,
    items: List<FilterItemModel>,
    onClear: (() -> Unit)?,
    onSave: (() -> Unit)?,
) {
    DropdownButton(
        modifier = modifier,
        icon = Icons.Outlined.FilterAlt,
        title = stringResource(Res.strings.filter_header_title),
        items = items,
        onClear = onClear,
        onSave = onSave,
    ) {
        VaultHomeScreenFilterPaneNumberOfItems(
            count = count,
        )

        FilterItems(
            items = items,
            predicate = {
                !it.id.startsWith("custom")
            },
        )
    }
}
