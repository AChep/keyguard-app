package com.artemchep.keyguard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.ui.theme.selectedContainer

@Composable
fun DropdownScope.DropdownMenuItemFlat(
    action: ContextItem,
) {
    when (action) {
        is ContextItem.Section -> {
            Section(
                modifier = Modifier
                    .fillMaxWidth(),
                text = action.title,
            )
        }

        is ContextItem.Custom -> {
            Column(
                modifier = Modifier
                    .widthIn(max = DropdownMinWidth),
            ) {
                action.content()
            }
        }

        is FlatItemAction -> {
            DropdownMenuItemFlat(action)
        }
    }
}

@Composable
fun DropdownScope.DropdownMenuItemFlat(
    action: FlatItemAction,
) {
    DropdownMenuItemFlatLayout(
        modifier = Modifier
            .padding(
                horizontal = 8.dp,
                vertical = 1.dp,
            )
            .clip(MaterialTheme.shapes.medium)
            .then(
                if (action.selected) {
                    val background = MaterialTheme.colorScheme.selectedContainer
                    Modifier
                        .background(background)
                } else {
                    Modifier
                }
            )
            .then(
                if (action.onClick != null) {
                    Modifier
                        .clickable {
                            action.onClick.invoke()
                            onDismissRequest()
                        }
                } else {
                    Modifier
                },
            )
            .minimumInteractiveComponentSize(),
    ) {
        val contentColor =
            if (action.selected) {
                val background = MaterialTheme.colorScheme.selectedContainer
                contentColorFor(background)
            } else {
                LocalContentColor.current
            }
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            FlatItemActionContent(
                action = action,
                compact = true,
            )
        }
    }
}

@Composable
fun DropdownScope.DropdownMenuItemFlatLayout(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .widthIn(max = DropdownMinWidth)
            .padding(
                horizontal = 8.dp,
                vertical = 4.dp,
            ),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
fun OptionsButton(
    actions: List<ContextItem> = emptyList(),
) {
    if (actions.isNotEmpty()) {
        OptionsButton(
            enabled = actions.isNotEmpty(),
        ) {
            actions.forEachIndexed { index, action ->
                DropdownMenuItemFlat(
                    action = action,
                )
            }
        }
    }
}

@Composable
fun OptionsButton(
    enabled: Boolean = true,
    content: @Composable DropdownScope.() -> Unit,
) {
    var isAutofillWindowShowing by remember {
        mutableStateOf(false)
    }

    if (!enabled) {
        // We can not autofill disabled text fields and we can not
        // tease user with it.
        isAutofillWindowShowing = false
    }

    Box {
        IconButton(
            enabled = enabled,
            onClick = {
                isAutofillWindowShowing = !isAutofillWindowShowing
            },
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = null,
            )
        }
        // Inject the dropdown popup to the bottom of the
        // content.
        val onDismissRequest = {
            isAutofillWindowShowing = false
        }
        KeyguardDropdownMenu(
            expanded = isAutofillWindowShowing,
            onDismissRequest = onDismissRequest,
        ) {
            content(this)
        }
    }
}
