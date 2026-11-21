package com.artemchep.keyguard.ui.focus

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties

/**
 * Enters the focus group below at the child of the [requester] when
 * focus comes from one of the [directions]. A group enters at its
 * top-left child otherwise, such as the last chip of a right-to-left
 * line, or the button nearest to the middle of the row focus came from.
 */
internal fun Modifier.enterAt(
    requester: FocusRequester,
    directions: List<FocusDirection>,
): Modifier = focusProperties {
    onEnter = {
        // The request redirects this focus change.
        if (requestedFocusDirection in directions) {
            requester.requestFocus()
        }
    }
}
