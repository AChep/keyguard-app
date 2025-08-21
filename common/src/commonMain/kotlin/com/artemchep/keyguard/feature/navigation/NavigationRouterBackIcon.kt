package com.artemchep.keyguard.feature.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.ui.surface.LocalSurfaceElevation
import com.artemchep.keyguard.ui.surface.surfaceNextGroupColorToElevationColor
import com.artemchep.keyguard.ui.theme.LocalExpressive

@Composable
fun NavigationIcon(
    modifier: Modifier = Modifier,
) {
    val visualStack = LocalNavigationNodeVisualStack.current
    val backVisible = visualStack.size > 1
    if (backVisible) {
        val navigationId by rememberUpdatedState(visualStack.last().id)
        val controller by rememberUpdatedState(LocalNavigationController.current)

        val containerColor = run {
            if (!LocalExpressive.current) {
                return@run Color.Unspecified
            }

            val surfaceElevation = LocalSurfaceElevation.current
            surfaceNextGroupColorToElevationColor(surfaceElevation.to)
        }
        IconButton(
            modifier = modifier,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = containerColor,
            ),
            onClick = {
                val intent = NavigationIntent.PopById(navigationId, exclusive = false)
                controller.queue(intent)
            },
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
        }
    }
}
