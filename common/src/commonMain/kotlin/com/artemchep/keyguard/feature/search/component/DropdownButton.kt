package com.artemchep.keyguard.feature.search.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.DropdownMinWidth
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.theme.badgeContainer

@Composable
fun <T> DropdownButton(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    items: List<T>,
    onClear: (() -> Unit)?,
    onSave: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var isExpanded by remember { mutableStateOf(false) }
    SideEffect {
        // Filters can not be shown if there's no content
        // available. Automatically hide the dropdown.
        if (items.isEmpty() && isExpanded) {
            isExpanded = false
        }
    }
    val onExpandRequest = remember {
        // lambda
        {
            isExpanded = true
        }
    }
    val onDismissRequest = remember {
        // lambda
        {
            isExpanded = false
        }
    }

    Box(
        modifier = modifier,
    ) {
        val visible = items.isNotEmpty()
        ExpandedIfNotEmptyForRow(
            valueOrNull = Unit.takeIf { visible },
        ) {
            IconButton(
                modifier = Modifier,
                enabled = items.isNotEmpty(),
                onClick = onExpandRequest,
            ) {
                Box {
                    Icon(icon, null)
                    AnimatedVisibility(
                        modifier = Modifier
                            .size(8.dp)
                            .align(Alignment.TopEnd),
                        visible = onClear != null,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    color = MaterialTheme.colorScheme.badgeContainer,
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
            }
        }

        // Inject the dropdown popup to the bottom of the
        // content.
        DropdownMenu(
            modifier = Modifier
                .widthIn(
                    min = DropdownMinWidth,
                    max = 320.dp,
                )
                .fillMaxWidth(),
            expanded = isExpanded,
            onDismissRequest = onDismissRequest,
        ) {
            DropdownHeader(
                modifier = Modifier
                    .fillMaxWidth(),
                title = title,
                actions = {
                    val updatedOnSave by rememberUpdatedState(onSave)
                    ExpandedIfNotEmptyForRow(
                        valueOrNull = onSave,
                    ) {
                        IconButton(
                            onClick = {
                                // At first we hide the popup
                                // menu, because it launches a
                                // new route.
                                onDismissRequest()
                                // Launch the screen.
                                updatedOnSave?.invoke()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Save,
                                contentDescription = null,
                            )
                        }
                    }
                    val updatedOnClear by rememberUpdatedState(onClear)
                    ExpandedIfNotEmptyForRow(
                        valueOrNull = onClear,
                    ) {
                        IconButton(
                            onClick = {
                                updatedOnClear?.invoke()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Clear,
                                contentDescription = null,
                            )
                        }
                    }
                },
            )

            content()
        }
    }
}
