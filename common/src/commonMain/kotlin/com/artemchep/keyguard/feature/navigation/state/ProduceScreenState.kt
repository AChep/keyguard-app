package com.artemchep.keyguard.feature.navigation.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.Flow

/**
 * `key` identifies this screen state's remembered/persisted scope.
 *
 * Allowed symbols:
 * - There is no explicit whitelist; storage accepts arbitrary strings.
 * - Existing keys commonly use `_`, and some use `:` for namespacing/instance identity.
 *
 * Recommended format:
 * - Use stable lowercase ASCII slugs with digits and underscores, for example `vault_list`.
 * - Use `:` only when deliberate namespacing is useful, for example `account:$accountId`.
 *
 * Avoid:
 * - Raw user input, unstable values, whitespace, and control characters.
 * - Treating `:`, `/`, or `@` as parseable separators. Navigation/persistence already compose
 *   internal keys with those symbols.
 */
@Composable
fun <T> produceScreenState(
    key: String,
    args: Array<Any?> = emptyArray(),
    rargs: Array<Any?> = emptyArray(),
    initial: T,
    init: suspend RememberStateFlowScope.() -> Flow<T>,
): T {
    val stateFlow by rememberScreenState(
        key = key,
        args = args,
        rargs = rargs,
        initial = initial,
        init = init,
    )
    return stateFlow
}

/**
 * Uses the same `key` rules as [produceScreenState].
 */
@Composable
fun <T> rememberScreenState(
    key: String,
    args: Array<Any?> = emptyArray(),
    rargs: Array<Any?> = emptyArray(),
    initial: T,
    init: suspend RememberStateFlowScope.() -> Flow<T>,
): State<T> = rememberScreenStateFlow(
    key = key,
    args = args,
    rargs = rargs,
    initial = initial,
    init = init,
).collectAsState()
