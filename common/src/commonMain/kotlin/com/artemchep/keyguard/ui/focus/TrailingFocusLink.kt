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
 * the focus target of the row, and the [buttons] on the buttons, in
 * their order from the start to the end.
 */
@Immutable
internal class TrailingFocusLink(
    val row: Modifier,
    val buttons: List<Modifier>,
) {
    /** The first button's modifier, or an empty one. */
    val button: Modifier get() = buttons.firstOrNull() ?: Modifier
}

private val EmptyTrailingFocusLink = TrailingFocusLink(Modifier, emptyList())

/**
 * Links a row to a trailing button inside of it while [enabled]: the
 * end key moves from the row to the button, the start key back. The 2D
 * search does not find the button, as it is inside of the row. Without
 * [enabled] both modifiers are empty.
 */
@Composable
internal fun rememberTrailingFocusLink(enabled: Boolean): TrailingFocusLink = remember(enabled) {
    if (enabled) {
        val chain = focusChain(2)
        TrailingFocusLink(row = chain[0], buttons = chain.subList(1, 2))
    } else {
        EmptyTrailingFocusLink
    }
}

/**
 * Like the one-button form, for the buttons that are [linked], in their
 * order from the start to the end. There is a modifier per flag: a button
 * that is not linked, such as one that is not shown or can not take
 * focus, gets an empty one, so the end key skips it. The end key stops
 * at the last linked button, as the search would find another button
 * of the row, such as one above it. Without [enabled], such as when the
 * row itself is not a focus target, all modifiers are empty.
 */
@Composable
internal fun rememberTrailingFocusLink(
    enabled: Boolean,
    linked: List<Boolean>,
): TrailingFocusLink = remember(enabled, linked) {
    val count = linked.count { it }
    if (!enabled || count == 0) {
        return@remember TrailingFocusLink(Modifier, linked.map { Modifier })
    }
    val chain = focusChain(count + 1)
    var next = 1
    TrailingFocusLink(
        row = chain[0],
        buttons = linked.map { isLinked ->
            when {
                !isLinked -> Modifier
                next == count -> chain[next++].then(StopAtEndModifier)
                else -> chain[next++]
            }
        },
    )
}

// Applied after the chain's own properties, which set no end on the
// last one, so the cancel is kept.
private val StopAtEndModifier = Modifier
    .focusProperties {
        end = FocusRequester.Cancel
    }

/**
 * Modifiers for [count] focus targets in a line, from the start to the
 * end: the end key moves to the next one, the start key to the previous
 * one, by the layout direction. Use each before its focus target.
 */
internal fun focusChain(count: Int): List<Modifier> {
    val requesters = List(count) { FocusRequester() }
    return requesters.mapIndexed { index, requester ->
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
