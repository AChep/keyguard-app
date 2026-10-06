package com.artemchep.keyguard.feature.home.vault.quicksearch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.artemchep.keyguard.feature.home.vault.component.SmartBadge
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.one_time_password
import com.artemchep.keyguard.res.password
import com.artemchep.keyguard.res.quick_search_autotype_choose_field
import com.artemchep.keyguard.res.username
import com.artemchep.keyguard.ui.KeyguardDropdownMenu
import com.artemchep.keyguard.ui.shortcut.ShortcutTooltip
import com.artemchep.keyguard.ui.shortcut.toText
import com.artemchep.keyguard.ui.theme.selectedContainer
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun QuickSearchAutotypeButton(
    action: QuickSearchAction,
    availableFields: List<QuickSearchAutotypeField>,
    selectedField: QuickSearchAutotypeField?,
    onAutotype: () -> Unit,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onAutotypeField: (QuickSearchAutotypeField) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val expanded = selectedField != null
    LaunchedEffect(expanded) {
        if (expanded) bringIntoViewRequester.bringIntoView()
    }
    Box(modifier = modifier.bringIntoViewRequester(bringIntoViewRequester)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmartBadge(
                title = action.title,
                text = action.shortcut?.toText()?.text,
                selected = action.selected,
                onClick = onAutotype,
            )
            Spacer(
                modifier = Modifier
                    .width(4.dp),
            )
            ShortcutTooltip(quickSearchAutotypeMenuShortcut) {
                IconButton(
                    modifier = Modifier.size(36.dp),
                    onClick = if (expanded) onDismissMenu else onOpenMenu,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowDropDown,
                        contentDescription = stringResource(Res.string.quick_search_autotype_choose_field),
                    )
                }
            }
        }
        KeyguardDropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissMenu,
            // Keep the search window focused; its preview handler owns menu navigation.
            properties = PopupProperties(focusable = false),
        ) {
            QuickSearchAutotypeField.entries.forEach { field ->
                QuickSearchAutotypeMenuItem(
                    field = field,
                    selected = field == selectedField,
                    enabled = field in availableFields,
                    onClick = { onAutotypeField(field) },
                )
            }
        }
    }
}

@Composable
private fun QuickSearchAutotypeMenuItem(
    field: QuickSearchAutotypeField,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val title = when (field) {
        QuickSearchAutotypeField.Username -> Res.string.username
        QuickSearchAutotypeField.Password -> Res.string.password
        QuickSearchAutotypeField.OneTimeCode -> Res.string.one_time_password
    }
    DropdownMenuItem(
        modifier = Modifier
            .semantics { this.selected = selected }
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.selectedContainer)
                else Modifier,
            ),
        text = { Text(stringResource(title)) },
        leadingIcon = {
            Text("#${field.number}")
        },
        enabled = enabled,
        onClick = onClick,
    )
}
