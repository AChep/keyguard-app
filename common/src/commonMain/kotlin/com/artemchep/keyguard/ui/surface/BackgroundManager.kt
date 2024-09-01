package com.artemchep.keyguard.ui.surface

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlin.uuid.Uuid

class BackgroundManager {
    private val surfaceColorsState = mutableStateMapOf<String, Color>()

    val colorHighest: Color
        @Composable
        get() {
            val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
            val surfaceContainerHigh = MaterialTheme.colorScheme.surfaceContainerHigh
            val surfaceContainerHighest = MaterialTheme.colorScheme.surfaceContainerHighest

            var maxPriority = 0
            surfaceColorsState.values.forEach { color ->
                val priority = when (color) {
                    surfaceContainer -> 1
                    surfaceContainerHigh -> 2
                    surfaceContainerHighest -> 3
                    else -> 0
                }
                if (maxPriority < priority) {
                    maxPriority = priority
                }
            }

            return when (maxPriority) {
                0 -> surfaceContainer
                1 -> surfaceContainerHigh
                2 -> surfaceContainerHighest
                else -> surfaceContainerHighest
            }
        }

    fun register(
        color: Color,
    ): () -> Unit {
        val key = Uuid.random().toString()
        surfaceColorsState[key] = color

        return {
            surfaceColorsState.remove(key)
        }
    }
}

private val globalBackgroundManager = BackgroundManager()

val LocalBackgroundManager = staticCompositionLocalOf {
    globalBackgroundManager
}

/**
 * Reports the background color to the background manager. This might affect the
 * highest elevation elements such as elevated toolbar or the navigation bar/rail.
 */
@Composable
fun ReportSurfaceColor() {
    val surfaceColor = LocalSurfaceColor.current
    val backgroundManager = LocalBackgroundManager.current
    DisposableEffect(
        surfaceColor,
        backgroundManager,
    ) {
        val unregister = backgroundManager.register(surfaceColor)
        onDispose {
            unregister()
        }
    }
}
