package com.artemchep.keyguard.feature.search.filter

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.artemchep.keyguard.feature.search.filter.component.VaultHomeScreenFilterPaneNumberOfItems
import com.artemchep.keyguard.feature.search.filter.model.FilterItemModel
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.toolbar.SmallToolbar
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun FilterScreen(
    modifier: Modifier = Modifier,
    count: Int?,
    items: List<FilterItemModel>,
    onClear: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    ScaffoldColumn(
        modifier = modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
//        topBar = {
//            SmallToolbar(
//                title = {
//                    Text(
//                        text = stringResource(Res.strings.filter_header_title),
//                        style = MaterialTheme.typography.titleMedium,
//                    )
//                },
//                actions = actions,
//                scrollBehavior = scrollBehavior,
//            )
//        },
        floatingActionState = run {
            val fabState = if (onClear != null) {
                FabState(
                    onClick = onClear,
                    model = null,
                )
            } else {
                null
            }
            rememberUpdatedState(newValue = fabState)
        },
        floatingActionButton = {
            DefaultFab(
                icon = {
                    IconBox(main = Icons.Outlined.Clear)
                },
                text = {
                    Text(
                        text = stringResource(Res.strings.reset),
                    )
                },
                color = MaterialTheme.colorScheme.secondaryContainer,
            )
        },
    ) {
        VaultHomeScreenFilterPaneNumberOfItems(
            count = count,
        )

        FilterItems(
            items = items,
        )
    }
}
