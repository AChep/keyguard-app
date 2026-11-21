package com.artemchep.keyguard.ui.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester

/**
 * The modifiers of a [rememberTrailingFocusLink]. Use the [row] before
 * the focus target of the row, and the [button] on the button.
 */
@Immutable
internal class TrailingFocusLink(
    val row: Modifier,
    val button: Modifier,
)

/**
 * Links a row to a trailing button inside of it while [enabled]: the
 * end key moves from the row to the button, the start key back. The 2D
 * search does not find the button, as it is inside of the row. Without
 * [enabled] both modifiers are empty.
 */
@Composable
internal fun rememberTrailingFocusLink(enabled: Boolean): TrailingFocusLink {
    val rowRequester = remember { FocusRequester() }
    val buttonRequester = remember { FocusRequester() }
    return remember(enabled) {
        if (enabled) {
            TrailingFocusLink(
                row = Modifier
                    .focusRequester(rowRequester)
                    .focusProperties { end = buttonRequester },
                button = Modifier
                    .focusRequester(buttonRequester)
                    .focusProperties { start = rowRequester },
            )
        } else {
            TrailingFocusLink(Modifier, Modifier)
        }
    }
}
