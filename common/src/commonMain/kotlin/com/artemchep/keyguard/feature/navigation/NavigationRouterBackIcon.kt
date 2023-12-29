package com.artemchep.keyguard.feature.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier

@Composable
fun NavigationIcon(
    modifier: Modifier = Modifier,
) {
    val visualStack = LocalNavigationNodeVisualStack.current
    val backVisible = visualStack.size > 1
    if (backVisible) {
        val navigationId by rememberUpdatedState(visualStack.last().id)
        val controller by rememberUpdatedState(LocalNavigationController.current)
        IconButton(
            modifier = modifier,
            onClick = {
                val intent = NavigationIntent.PopById(navigationId, exclusive = false)
                controller.queue(intent)
            },
        ) {
            Icon(Icons.Outlined.ArrowBack, null)
        }
    }
}
