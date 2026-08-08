package com.artemchep.keyguard.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.theme.combineAlpha

private const val DIALOG_WIDTH_FRACTION = 0.9f

private const val DIALOG_HEIGHT_FRACTION = 0.9f

/**
 * The container color for an activity that uses the `Theme.Keyguard.Dialog`
 * theme.
 *
 * The window itself is translucent and full-screen, so the root surface must
 * not paint anything; the scrim and the card are drawn by
 * [DialogActivityWindow] instead.
 */
@Composable
internal fun dialogActivityContainerColor(): Color = Color.Transparent

/**
 * The content color for an activity that uses the `Theme.Keyguard.Dialog`
 * theme. A transparent container has no sensible `contentColorFor()`
 * counterpart, so it has to be provided explicitly.
 */
@Composable
internal fun dialogActivityContentColor(): Color = MaterialTheme.colorScheme.onSurface

/**
 * Draws a dimmed scrim with a centered, size constrained card on top of it,
 * making a translucent full-screen activity look like a dialog.
 *
 * @param onDismiss Called when the user taps outside of the card.
 * @param content The content of the card.
 */
@Composable
internal fun DialogActivityWindow(
    onDismiss: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val animation = rememberDialogActivityWindowAnimation()
    val dismissInteractionSource = remember { MutableInteractionSource() }
    val contentInteractionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(animation.dimColor)
            .clickable(
                interactionSource = dismissInteractionSource,
                indication = null,
            ) {
                onDismiss()
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = animation.contentScale
                    scaleY = animation.contentScale
                    alpha = animation.contentAlpha
                    translationY = animation.contentTranslationY.toPx()
                }
                .heightIn(max = 520.dp)
                .widthIn(max = 380.dp)
                .fillMaxWidth(fraction = DIALOG_WIDTH_FRACTION)
                .fillMaxHeight(fraction = DIALOG_HEIGHT_FRACTION)
                .clickable(
                    interactionSource = contentInteractionSource,
                    indication = null,
                ) {},
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                content = content,
            )
        }
    }
}

@Immutable
private data class DialogActivityWindowAnimation(
    val dimColor: Color,
    val contentScale: Float,
    val contentAlpha: Float,
    val contentTranslationY: Dp,
)

/**
 * Fades the scrim in and pops the card up, starting as soon as the window
 * enters the composition.
 */
@Composable
private fun rememberDialogActivityWindowAnimation(): DialogActivityWindowAnimation {
    var dimColorTarget by remember {
        val initialColor = Color.Black
            .combineAlpha(0.0f)
        mutableStateOf(initialColor)
    }
    var contentScaleTarget by remember {
        val initialScale = 0.8f
        mutableFloatStateOf(initialScale)
    }
    var contentAlphaTarget by remember {
        val initialAlpha = 0.0f
        mutableFloatStateOf(initialAlpha)
    }
    var contentTranslationYTarget by remember {
        val initialY = 48.dp
        mutableStateOf(initialY)
    }
    LaunchedEffect(Unit) {
        val targetDimAlpha = 0.44f
        dimColorTarget = Color.Black
            .copy(alpha = targetDimAlpha)
        contentScaleTarget = 1f
        contentAlphaTarget = 1f
        contentTranslationYTarget = 0.dp
    }

    val dimColor by animateColorAsState(
        targetValue = dimColorTarget,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    val contentScale by animateFloatAsState(
        targetValue = contentScaleTarget,
        animationSpec = tween(durationMillis = 300),
    )
    val contentAlpha by animateFloatAsState(
        targetValue = contentAlphaTarget,
        animationSpec = tween(durationMillis = 180),
    )
    val contentTranslationY by animateDpAsState(
        targetValue = contentTranslationYTarget,
        animationSpec = tween(durationMillis = 300),
    )
    return DialogActivityWindowAnimation(
        dimColor = dimColor,
        contentScale = contentScale,
        contentAlpha = contentAlpha,
        contentTranslationY = contentTranslationY,
    )
}
