package com.artemchep.keyguard.ui.icons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.HighEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha

@Suppress("NOTHING_TO_INLINE")
inline fun icon(
    main: ImageVector,
    secondary: ImageVector? = null,
): @Composable () -> Unit = {
    IconBox(
        main = main,
        secondary = secondary,
    )
}

@Suppress("NOTHING_TO_INLINE")
inline fun <T> icon(
    main: ImageVector,
    secondary: ImageVector? = null,
): @Composable T.() -> Unit = {
    IconBox(
        main = main,
        secondary = secondary,
    )
}

@Composable
fun IconBox(
    main: ImageVector,
    secondary: ImageVector? = null,
    secondaryBackground: @Composable () -> Color = { MaterialTheme.colorScheme.surfaceVariant },
    secondaryTint: @Composable () -> Color = { MaterialTheme.colorScheme.onSurfaceVariant },
) {
    Box {
        Icon(main, null)
        if (secondary != null) {
            val alphaColor = LocalContentColor.current.alpha
            val backgroundColor = secondaryBackground()
                .copy(alpha = HighEmphasisAlpha * alphaColor)
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .background(backgroundColor, CircleShape),
            ) {
                Icon(
                    secondary,
                    null,
                    Modifier
                        .padding(1.dp)
                        .size(12.dp),
                    tint = secondaryTint()
                        .combineAlpha(alphaColor),
                )
            }
        }
    }
}

@Composable
fun IconBoxContainer(
    main: @Composable () -> Unit,
    secondary: (@Composable () -> Unit)? = null,
    secondaryBackground: @Composable () -> Color = { MaterialTheme.colorScheme.surfaceVariant },
    secondaryTint: @Composable () -> Color = { MaterialTheme.colorScheme.onSurfaceVariant },
    compact: Boolean = false,
) {
    Box {
        main()
        if (secondary != null) {
            val backgroundColor = secondaryBackground()
                .copy(alpha = HighEmphasisAlpha)
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .then(
                        if (compact) {
                            Modifier
                                .graphicsLayer {
                                    translationX = density * 4f
                                    translationY = density * 4f
                                }
                        } else {
                            Modifier
                        },
                    )
                    .background(backgroundColor, CircleShape),
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides secondaryTint(),
                ) {
                    Box(
                        Modifier
                            .padding(1.dp)
                            .size(12.dp),
                    ) {
                        secondary()
                    }
                }
            }
        }
    }
}

@Suppress("NOTHING_TO_INLINE")
inline fun iconSmall(
    main: ImageVector,
    secondary: ImageVector? = null,
): @Composable () -> Unit = {
    IconSmallBox(
        main = main,
        secondary = secondary,
    )
}

@Suppress("NOTHING_TO_INLINE")
inline fun <T> iconSmall(
    main: ImageVector,
    secondary: ImageVector? = null,
): @Composable T.() -> Unit = {
    IconSmallBox(
        main = main,
        secondary = secondary,
    )
}

@Composable
fun IconSmallBox(
    main: ImageVector,
    secondary: ImageVector? = null,
    secondaryBackground: @Composable () -> Color = { MaterialTheme.colorScheme.surfaceVariant },
    secondaryTint: @Composable () -> Color = { MaterialTheme.colorScheme.onSurfaceVariant },
) {
    IconBoxContainer(
        main = {
            Icon(main, null)
        },
        secondary = if (secondary != null) {
            // composable
            {
                Icon(
                    secondary,
                    null,
                    tint = secondaryTint(),
                )
            }
        } else {
            null
        },
        secondaryBackground = secondaryBackground,
        secondaryTint = secondaryTint,
        compact = true,
    )
}
