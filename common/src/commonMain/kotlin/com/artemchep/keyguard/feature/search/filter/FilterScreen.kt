package com.artemchep.keyguard.feature.search.filter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.search.filter.component.VaultHomeScreenFilterPaneNumberOfItems
import com.artemchep.keyguard.feature.search.filter.model.FilterItemModel
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.SmallFab
import com.artemchep.keyguard.ui.icons.IconBox
import org.jetbrains.compose.resources.stringResource

@Composable
fun FilterScreen(
    modifier: Modifier = Modifier,
    count: Int?,
    items: List<FilterItemModel>,
    onClear: (() -> Unit)?,
    onSave: (() -> Unit)? = null,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    ScaffoldColumn(
        modifier = modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
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
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val updatedOnSave by rememberUpdatedState(onSave)
                SmallFab(
                    onClick = {
                        updatedOnSave?.invoke()
                    },
                    icon = {
                        IconBox(main = Icons.Outlined.Save)
                    },
                )
                DefaultFab(
                    icon = {
                        IconBox(main = Icons.Outlined.Clear)
                    },
                    text = {
                        Text(
                            text = stringResource(Res.string.reset),
                        )
                    },
                    color = MaterialTheme.colorScheme.secondaryContainer,
                )
            }
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
