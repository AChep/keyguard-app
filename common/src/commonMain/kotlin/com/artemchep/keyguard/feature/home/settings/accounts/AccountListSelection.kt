package com.artemchep.keyguard.feature.home.settings.accounts

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.selection_n_selected
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.OptionsButton
import com.artemchep.keyguard.ui.icons.SyncIcon
import com.artemchep.keyguard.ui.selection.SelectionBar
import org.jetbrains.compose.resources.stringResource

@Composable
fun AccountsSelection(
    selection: AccountListState.Selection?,
) {
    ExpandedIfNotEmpty(
        valueOrNull = selection,
    ) { selection ->
        SelectionBar(
            title = {
                val text = stringResource(Res.string.selection_n_selected, selection.count)
                Text(text, maxLines = 2)
            },
            trailing = {
                val updatedOnSelectAll by rememberUpdatedState(selection.onSelectAll)
                IconButton(
                    enabled = updatedOnSelectAll != null,
                    onClick = {
                        updatedOnSelectAll?.invoke()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SelectAll,
                        contentDescription = null,
                    )
                }
                IconButton(
                    enabled = selection.onSync != null,
                    onClick = {
                        selection.onSync?.invoke()
                    },
                ) {
                    SyncIcon(rotating = false)
                }
                OptionsButton(
                    actions = selection.actions,
                )
            },
            onClear = selection.onClear,
        )
    }
}