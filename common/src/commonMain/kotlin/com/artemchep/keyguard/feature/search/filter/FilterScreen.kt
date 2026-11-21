package com.artemchep.keyguard.feature.search.filter

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.search.filter.component.VaultHomeScreenFilterPaneNumberOfItems
import com.artemchep.keyguard.feature.search.filter.model.FilterItemModel
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.SmallFab
import com.artemchep.keyguard.ui.focus.arrowKeyFocusNavigation
import com.artemchep.keyguard.ui.focus.restoreFocusedChildOrRequestFocus
import com.artemchep.keyguard.ui.focus.saveFocusedChildOnExit
import com.artemchep.keyguard.ui.icons.IconBox
import org.jetbrains.compose.resources.stringResource

/**
 * The arrow keys move focus between the filters. With [onFocusContent],
 * the key toward the content at the end edge of the filters calls it,
 * and Left or Right from the content focuses the filter focused last,
 * else the first one.
 */
@Composable
fun FilterScreen(
    modifier: Modifier = Modifier,
    count: Int?,
    items: List<FilterItemModel>,
    onClear: (() -> Unit)?,
    onSave: (() -> Unit)? = null,
    onFocusContent: (() -> Unit)? = null,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val panelRequester = remember { FocusRequester() }
    val onStuck = rememberFocusContentOnStuck(onFocusContent)
    val panelFocus = rememberFilterPanelFocus(panelRequester, onStuck, restoreFromSide = onFocusContent != null)
    val fabFocus = remember { FabFocus() }
    // The FABs show only while a filter is active,
    // so Reset takes away the FAB that has focus.
    if (onClear == null && fabFocus.focused) {
        FocusFirstFilterEffect(panelRequester)
    }
    ScaffoldColumn(
        modifier = modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .then(panelFocus),
        // The scroll container's own focus target.
        columnModifier = Modifier
            .saveFocusedChildOnExit(),
        topAppBarScrollBehavior = scrollBehavior,
        floatingActionState = rememberUpdatedState(
            newValue = onClear?.let { FabState(onClick = it, model = null) },
        ),
        floatingActionButton = {
            Column(
                modifier = Modifier
                    .onFocusChanged { fabFocus.focused = it.hasFocus },
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
            // The end edge is where the content takes over.
            endEdge = if (onFocusContent != null) FocusRequester.Cancel else FocusRequester.Default,
        )
    }
}

/**
 * Whether the FABs hold focus. A plain field, so a focus change does
 * not recompose: the composition that removes the FABs reads it.
 */
private class FabFocus {
    var focused = false
}

/**
 * The arrow keys move between the filters. Each group on the way to a
 * filter remembers its focused child, so a restore returns to the filter.
 * With [restoreFromSide], Left or Right into the filters restores too.
 * The modifier is remembered, so it stays equal and the scaffold skips.
 */
@Composable
private fun rememberFilterPanelFocus(
    requester: FocusRequester,
    onStuck: (FocusDirection) -> Boolean,
    restoreFromSide: Boolean,
): Modifier = remember(requester, onStuck, restoreFromSide) {
    Modifier
        .arrowKeyFocusNavigation(onStuck = onStuck)
        .focusRequester(requester)
        .focusProperties {
            if (restoreFromSide) {
                onEnter = {
                    val side = requestedFocusDirection == FocusDirection.Left ||
                        requestedFocusDirection == FocusDirection.Right
                    // The request redirects this focus change. It does not
                    // enter again, as it focuses a child of this group.
                    if (side) {
                        requester.restoreFocusedChildOrRequestFocus(FocusDirection.Enter)
                    }
                }
            }
        }
        .saveFocusedChildOnExit()
        .focusGroup()
}

/**
 * Focuses the first filter in keyboard input mode. Call it in the
 * composition that removes the focused FABs, before they leave.
 */
@Composable
private fun FocusFirstFilterEffect(
    panelRequester: FocusRequester,
) {
    val inputModeManager = LocalInputModeManager.current
    SideEffect {
        if (inputModeManager.inputMode == InputMode.Keyboard) {
            panelRequester.requestFocus(FocusDirection.Enter)
        }
    }
}

/**
 * Calls [onFocusContent] for the arrow key toward the content,
 * which is on the end side of the filters.
 */
@Composable
private fun rememberFocusContentOnStuck(
    onFocusContent: (() -> Unit)?,
): (FocusDirection) -> Boolean {
    val contentDirection = when (LocalLayoutDirection.current) {
        LayoutDirection.Ltr -> FocusDirection.Right
        LayoutDirection.Rtl -> FocusDirection.Left
    }
    val updatedOnFocusContent by rememberUpdatedState(onFocusContent)
    return remember(contentDirection) {
        { direction ->
            val focusContent = updatedOnFocusContent
                .takeIf { direction == contentDirection }
            focusContent?.invoke()
            focusContent != null
        }
    }
}
