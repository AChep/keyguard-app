package com.artemchep.keyguard.ui

//import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.ElevationOverlay
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

// Menu open/close animation.
private const val InTransitionDuration = 180
private const val OutTransitionDuration = 125

@Composable
fun WunderPopup(
    modifier: Modifier = Modifier,
    onDismissRequest: () -> Unit,
    expanded: Boolean = true,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable () -> Unit,
) {
    val expandedStates = remember {
        MutableTransitionState(false)
    }
    expandedStates.targetState = expanded
    if (!expandedStates.currentState && !expandedStates.targetState) {
        return
    }

    // Menu open/close animation.
    val transition = updateTransition(expandedStates, "DropDownMenu")
    val alpha by transition.animateFloat(
        transitionSpec = {
            if (false isTransitioningTo true) {
                // Dismissed to expanded
                tween(durationMillis = InTransitionDuration)
            } else {
                // Expanded to dismissed.
                tween(durationMillis = OutTransitionDuration)
            }
        },
    ) {
        if (it) {
            // Menu is expanded.
            1f
        } else {
            // Menu is dismissed.
            0f
        }
    }

    Popup(
        popupPositionProvider = object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset = IntOffset.Zero
        },
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(
            focusable = true,
        ),
        //onPreviewKeyEvent = { false },
        //onKeyEvent = {
        // TODO:  && it.awtEventOrNull?.keyCode == KeyEvent.VK_ESCAPE
        //if (it.type == KeyEventType.KeyDown) {
        //    onDismissRequest()
        //    true
        //} else {
        //        false
        //}
        //},
    ) {
        val scrimColor = MaterialTheme.colorScheme.scrim
            .copy(alpha = 0.32f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(alpha)
                .background(scrimColor)
                .pointerInput(onDismissRequest) {
                    detectTapGestures(onPress = { onDismissRequest() })
                },
        )
    }

    // Popups on the desktop are by default embedded in the component in which
    // they are defined and aligned within its bounds. But an [AlertDialog] needs
    // to be aligned within the window, not the parent component, so we cannot use
    // [alignment] property of [Popup] and have to use [Box] that fills all the
    // available space. Also [Box] provides a dismiss request feature when clicked
    // outside of the [AlertDialog] content.
    val transformOriginState = remember { mutableStateOf(TransformOrigin.Center) }
    val density = LocalDensity.current
    // The original [DropdownMenuPositionProvider] is not yet suitable for large screen devices,
    // so we need to make additional checks and adjust the position of the [DropdownMenu] to
    // avoid content being cut off if the [DropdownMenu] contains too many items.
    // See: https://github.com/JetBrains/compose-jb/issues/1388
    val popupPositionProvider = DesktopDropdownMenuPositionProvider(
        offset,
        density,
    ) { parentBounds, menuBounds ->
        transformOriginState.value = calculateTransformOrigin(parentBounds, menuBounds)
    }
    Popup(
        popupPositionProvider = popupPositionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(
            focusable = true,
        ),
        //onPreviewKeyEvent = { false },
        //onKeyEvent = {
        // TODO:  && it.awtEventOrNull?.keyCode == KeyEvent.VK_ESCAPE
        //if (it.type == KeyEventType.KeyDown) {
        //    onDismissRequest()
        //    true
        //} else {
        //        false
        //}
        //},
    ) {
        val scale by transition.animateFloat(
            transitionSpec = {
                if (false isTransitioningTo true) {
                    // Dismissed to expanded
                    tween(
                        durationMillis = InTransitionDuration,
                    )
                } else {
                    // Expanded to dismissed.
                    tween(
                        durationMillis = OutTransitionDuration,
                    )
                }
            },
        ) {
            if (it) {
                // Menu is expanded.
                1f
            } else {
                // Menu is dismissed.
                0.9f
            }
        }
        val elevation = 1.dp
        val maxWidth = 418.dp
        Surface(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth(0.8f)
                .fillMaxHeight()
                .pointerInput(onDismissRequest) {
                    detectTapGestures(
                        onPress = {
                            // Workaround to disable clicks on Surface background
                            // https://github.com/JetBrains/compose-jb/issues/2581
                        },
                    )
                }
                .graphicsLayer {
                    this.translationX = (1f - scale) * maxWidth.toPx()
                    this.alpha = alpha
                    transformOrigin = transformOriginState.value
                },
            shape = MaterialTheme.shapes.large
                .copy(
                    topEnd = CornerSize(0.dp),
                    bottomEnd = CornerSize(0.dp),
                ),
            tonalElevation = elevation,
            shadowElevation = elevation,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize(),
            ) {
                content()
            }
        }
    }
}

@Immutable
private data class DesktopDropdownMenuPositionProvider(
    val contentOffset: DpOffset,
    val density: Density,
    // callback
    val onPositionCalculated: (IntRect, IntRect) -> Unit = { _, _ -> },
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // The min margin above and below the menu, relative to the screen.
        val verticalMargin = with(density) { 48.dp.roundToPx() }
        // The content offset specified using the dropdown offset parameter.
        val contentOffsetY = with(density) { contentOffset.y.roundToPx() }

        // Compute horizontal position.
        val toDisplayRight = windowSize.width - popupContentSize.width
        val toDisplayLeft = 0
        val x = if (layoutDirection == LayoutDirection.Ltr) {
            toDisplayRight
        } else {
            toDisplayLeft
        }

        // Compute vertical position.
        val toBottom = maxOf(anchorBounds.bottom + contentOffsetY, verticalMargin)
        val toTop = anchorBounds.top - contentOffsetY - popupContentSize.height
        val toCenter = anchorBounds.top - popupContentSize.height / 2
        val toDisplayBottom = windowSize.height - popupContentSize.height - verticalMargin
        var y = sequenceOf(toBottom, toTop, toCenter, toDisplayBottom).firstOrNull {
            it >= verticalMargin &&
                    it + popupContentSize.height <= windowSize.height - verticalMargin
        } ?: toTop

        // Desktop specific vertical position checking
        val aboveAnchor = anchorBounds.top + contentOffsetY
        val belowAnchor = windowSize.height - anchorBounds.bottom - contentOffsetY

        if (belowAnchor >= aboveAnchor) {
            y = anchorBounds.bottom + contentOffsetY
        }

        if (y + popupContentSize.height > windowSize.height) {
            y = windowSize.height - popupContentSize.height
        }

        y = y.coerceAtLeast(0)

        onPositionCalculated(
            anchorBounds,
            IntRect(x, y, x + popupContentSize.width, y + popupContentSize.height),
        )
        return IntOffset(x, y)
    }
}

private fun calculateTransformOrigin(
    parentBounds: IntRect,
    menuBounds: IntRect,
): TransformOrigin {
    val pivotX = when {
        menuBounds.left >= parentBounds.right -> 0f
        menuBounds.right <= parentBounds.left -> 1f
        menuBounds.width == 0 -> 0f
        else -> {
            val intersectionCenter =
                (
                        kotlin.math.max(parentBounds.left, menuBounds.left) +
                                kotlin.math.min(parentBounds.right, menuBounds.right)
                        ) / 2
            (intersectionCenter - menuBounds.left).toFloat() / menuBounds.width
        }
    }
    val pivotY = when {
        menuBounds.top >= parentBounds.bottom -> 0f
        menuBounds.bottom <= parentBounds.top -> 1f
        menuBounds.height == 0 -> 0f
        else -> {
            val intersectionCenter =
                (
                        kotlin.math.max(parentBounds.top, menuBounds.top) +
                                kotlin.math.min(parentBounds.bottom, menuBounds.bottom)
                        ) / 2
            (intersectionCenter - menuBounds.top).toFloat() / menuBounds.height
        }
    }
    return TransformOrigin(pivotX, pivotY)
}

@Composable
private fun surfaceColorAtElevation(
    color: Color,
    elevationOverlay: ElevationOverlay?,
    absoluteElevation: Dp,
): Color {
    return if (color == MaterialTheme.colorScheme.surface && elevationOverlay != null) {
        elevationOverlay.apply(color, absoluteElevation)
    } else {
        color
    }
}
