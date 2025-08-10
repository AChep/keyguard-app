package com.artemchep.keyguard.ui.tabs

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.resources.stringResource
import kotlinx.collections.immutable.ImmutableList

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T : TabItem> SegmentedButtonGroup(
    tabState: State<T>,
    tabs: ImmutableList<T>,
    onClick: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val updatedOnClick by rememberUpdatedState(onClick)
    ButtonGroup(
        overflowIndicator = {
            // Do nothing
        },
        modifier = modifier,
    ) {
        tabs.forEachIndexed { index, tab ->
            customItem(
                buttonGroupContent = {
                    val checked = tabState.value == tab
                    val interactionSource = remember {
                        MutableInteractionSource()
                    }
                    ToggleButton(
                        modifier = Modifier
                            .animateWidth(interactionSource)
                            .weight(1f),
                        checked = checked,
                        onCheckedChange = {
                            updatedOnClick(tab)
                        },
                    ) {
                        Text(
                            text = stringResource(tab.title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                menuContent = { state ->
                    val checked = tabState.value == tab
                    DropdownMenuItem(
                        enabled = checked,
                        text = {
                            Text(
                                text = stringResource(tab.title),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            updatedOnClick(tab)
                            state.dismiss()
                        },
                    )
                },
            )
        }
    }
}
