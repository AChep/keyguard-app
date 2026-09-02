package com.artemchep.keyguard.ui.tabs

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupMenuState
import androidx.compose.material3.ButtonGroupScope
import androidx.compose.material3.DropdownMenuItem
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
        overflowIndicator = { menuState -> SegmentedOverflowIndicator(menuState) },
        expandedRatio = 0.05f,
        modifier = modifier,
    ) {
        tabs.forEachIndexed { index, tab ->
            val interactionSource = interactionSourcesState[tab.key]
                ?: MutableInteractionSource()
            customItem(
                buttonGroupContent = {
                    SegmentedToggleButton(
                        tab = tab,
                        checked = tabState.value == tab,
                        interactionSource = interactionSource,
                        weight = weight,
                        onClick = updatedOnClick,
                    )
                },
                menuContent = { state ->
                    SegmentedMenuItem(tab, state, updatedOnClick)
                },
            )
        }
    }
}

@Composable
private fun SegmentedOverflowIndicator(menuState: ButtonGroupMenuState) {
    FilledIconButton(
        onClick = {
            if (menuState.isShowing) {
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
}

@Composable
private fun <T : TabItem> ButtonGroupScope.SegmentedToggleButton(
    tab: T,
    checked: Boolean,
    interactionSource: MutableInteractionSource,
    weight: Float,
    onClick: (T) -> Unit,
) {
    ToggleButton(
        modifier = Modifier
            .animateWidth(interactionSource)
            .then(
                if (weight.isNaN()) {
                    Modifier
                } else Modifier.weight(weight),
            ),
        checked = checked,
        onCheckedChange = { onClick(tab) },
        interactionSource = interactionSource,
    ) {
        SegmentedTabTitle(tab)
    }
}

@Composable
private fun <T : TabItem> SegmentedMenuItem(
    tab: T,
    menuState: ButtonGroupMenuState,
    onClick: (T) -> Unit,
) {
    DropdownMenuItem(
        enabled = true,
        text = { SegmentedTabTitle(tab) },
        onClick = {
            onClick(tab)
            menuState.dismiss()
        },
    )
}

@Composable
private fun SegmentedTabTitle(tab: TabItem) {
    Text(
        text = textResource(tab.title),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
