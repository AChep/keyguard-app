package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

@Composable
fun navigationNodeStack(): String {
    val stack by rememberUpdatedState(LocalNavigationNodeLogicalStack.current)
    val value by remember {
        derivedStateOf {
            navigationNodeStack(logicalStack = stack)
        }
    }
    return value
}

fun navigationNodeStack(
    logicalStack: List<NavigationEntry>,
): String = logicalStack
    .joinToString(separator = "/") { it.id }
