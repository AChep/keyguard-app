package com.artemchep.keyguard.feature.navigation

import androidx.compose.runtime.Composable

@Composable
fun navigationNextEntryOrNull(): NavigationEntry? {
    val screenId = LocalNavigationEntry.current.id
    val screenStack = LocalNavigationRouter.current.value

    val backStackIndex = screenStack
        .indexOfFirst { it.id == screenId }
        // take the next screen
        .inc()
    return screenStack.getOrNull(backStackIndex)
}
