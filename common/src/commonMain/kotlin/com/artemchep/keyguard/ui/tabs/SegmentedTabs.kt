package com.artemchep.keyguard.ui.tabs

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.artemchep.keyguard.feature.localization.textResource
import kotlinx.collections.immutable.ImmutableList

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T : TabItem> SegmentedButtonGroup(
    tabState: State<T?>,
    tabs: ImmutableList<T>,
    onClick: (T) -> Unit,
    modifier: Modifier = Modifier,
    weight: Float = Float.NaN,
) {
    val interactionSourcesState = remember {
        mutableStateMapOf<String, MutableInteractionSource>()
    }
    // Persist the interaction sources between tabs
    // update. Otherwise the scaling animation gets
    // stuck.
    tabs.forEach { tab ->
        val key = tab.key
        if (key !in interactionSourcesState) {
            val value = MutableInteractionSource()
            interactionSourcesState.put(key, value)
        }
    }

    val updatedOnClick by rememberUpdatedState(onClick)
    ButtonGroup(
        overflowIndicator = { menuState ->
            FilledIconButton(
                onClick = {
                    if (menuState.isExpanded) {
                        menuState.dismiss()
                    } else {
                        menuState.show()
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = null,
                )
            }
        },
        modifier = modifier,
    ) {
        tabs.forEachIndexed { index, tab ->
            val interactionSource = interactionSourcesState[tab.key]
                ?: MutableInteractionSource()
            customItem(
                buttonGroupContent = {
                    val checked = tabState.value == tab
                    ToggleButton(
                        modifier = Modifier
                            .animateWidth(interactionSource)
                            .then(
                                if (weight.isNaN()) {
                                    Modifier
                                } else Modifier
                                    .weight(weight),
                            ),
                        checked = checked,
                        onCheckedChange = {
                            updatedOnClick(tab)
                        },
                        interactionSource = interactionSource,
                    ) {
                        Text(
                            text = textResource(tab.title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                menuContent = { state ->
                    DropdownMenuItem(
                        enabled = true,
                        text = {
                            Text(
                                text = textResource(tab.title),
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
