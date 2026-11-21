package com.artemchep.keyguard.ui.focus

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import com.artemchep.keyguard.ui.FabScope

/**
 * Links the bars and the floating action buttons of a scaffold to its
 * content. The content lies under them, so an arrow key search from a
 * bar or a button passes over it.
 *
 * Each modifier wraps its slot, so its focus properties apply to the
 * first focus targets inside of the slot. A `null` modifier leaves its
 * slot as it is.
 */
@Immutable
class ScaffoldContentFocus internal constructor(
    internal val topBar: Modifier? = null,
    internal val bottomBar: Modifier? = null,
    internal val fabs: Modifier? = null,
)

/**
 * Links the bars to the [content] while [enabled]: Down from
 * the top bar and Up from the bottom bar move to the [content].
 */
@Composable
internal fun rememberScaffoldContentFocus(
    content: FocusRequester,
    enabled: Boolean = true,
): ScaffoldContentFocus = remember(content, enabled) {
    ScaffoldContentFocus(
        topBar = Modifier
            .focusProperties {
                if (enabled) down = content
            },
        bottomBar = Modifier
            .focusProperties {
                if (enabled) up = content
            },
    )
}

/**
 * Modifiers for the [count] buttons of a [ListFocus.rememberScaffoldContentFocus],
 * in their order from the start to the end. Left and Right move to the
 * previous or the next button, by the layout direction, also if the
 * buttons are stacked.
 */
@Composable
internal fun FabScope.rememberFabFocusOrder(count: Int): List<Modifier> = remember(count) {
    val requesters = List(count) { FocusRequester() }
    requesters.mapIndexed { index, requester ->
        val previous = requesters.getOrNull(index - 1)
        val next = requesters.getOrNull(index + 1)
        Modifier
            .focusRequester(requester)
            .focusProperties {
                if (previous != null) start = previous
                if (next != null) end = next
            }
    }
}

/**
 * The [slot] wrapped in a box with the [modifier],
 * or the [slot] itself if the [modifier] is `null`.
 */
@Composable
internal fun rememberFocusSlot(
    modifier: Modifier?,
    slot: @Composable () -> Unit,
): @Composable () -> Unit {
    if (modifier == null) return slot
    return remember(modifier, slot) {
        {
            Box(modifier = modifier) {
                slot()
            }
        }
    }
}
