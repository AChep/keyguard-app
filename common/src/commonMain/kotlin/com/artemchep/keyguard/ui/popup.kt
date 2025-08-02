package com.artemchep.keyguard.ui

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.artemchep.keyguard.platform.leIme
import com.artemchep.keyguard.platform.leSystemBars

// Menu open/close animation.
private const val InTransitionDuration = 180
private const val OutTransitionDuration = 125

@Composable
fun DialogPopup(
    onDismissRequest: () -> Unit,
    expanded: Boolean = true,
    content: @Composable () -> Unit,
) {
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
    val popupPositionProvider = DesktopDialogPositionProvider(
        density,
    )
    BasicPopup(
        contentModifier = Modifier
            .padding(16.dp),
        graphicsModifier = { alpha, scale ->
            this.translationY = (1f - scale) * 512.dp.toPx()
            this.scaleX = scale
            this.scaleY = scale
            this.alpha = alpha
            transformOrigin = transformOriginState.value
        },
        onDismissRequest = onDismissRequest,
        expanded = expanded,
        popupPositionProvider = popupPositionProvider,
    ) {
        content()
    }
}

@Composable
fun SheetPopup(
    onDismissRequest: () -> Unit,
    expanded: Boolean = true,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable () -> Unit,
) {
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
    BasicPopup(
        contentModifier = Modifier
            .fillMaxSize(),
        graphicsModifier = { alpha, scale ->
            this.translationX = (1f - scale) * 496.dp.toPx()
            this.alpha = alpha
            transformOrigin = transformOriginState.value
        },
        onDismissRequest = onDismissRequest,
        expanded = expanded,
        popupPositionProvider = popupPositionProvider,
        shape = MaterialTheme.shapes.extraLarge
            .copy(
                topEnd = CornerSize(0.dp),
                bottomEnd = CornerSize(0.dp),
            ),
    ) {
        content()
    }
}

@Composable
private fun BasicPopup(
    contentModifier: Modifier = Modifier,
    graphicsModifier: GraphicsLayerScope.(alpha: Float, scale: Float) -> Unit,
    onDismissRequest: () -> Unit,
    expanded: Boolean,
    popupPositionProvider: PopupPositionProvider,
    shape: Shape = MaterialTheme.shapes.extraLarge,
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
    val transition = rememberTransition(expandedStates, "BasicPopup")
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

    Popup(
        popupPositionProvider = popupPositionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(
            focusable = true,
        ),
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
        val maxWidth = 560.dp
        val verticalInsets = WindowInsets.leSystemBars
            .union(WindowInsets.leIme)
        Surface(
            modifier = Modifier
                .windowInsetsPadding(verticalInsets)
                .consumeWindowInsets(verticalInsets)
                .widthIn(
                    min = 280.dp,
                    max = maxWidth,
                )
                .pointerInput(onDismissRequest) {
                    detectTapGestures(
                        onPress = {
                            // Workaround to disable clicks on Surface background
                            // https://github.com/JetBrains/compose-jb/issues/2581
                        },
                    )
                }
                .then(contentModifier)
                .graphicsLayer {
                    graphicsModifier(this, alpha, scale)
                },
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 1.dp,
        ) {
            CompositionLocalProvider(
                LocalAbsoluteTonalElevation provides 16.dp,
            ) {
                Column(
                    modifier = Modifier,
                ) {
                    content()
                }
            }
        }
    }
}

@Immutable
private data class DesktopDialogPositionProvider(
    val density: Density,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (windowSize.width - popupContentSize.width) / 2
        val y = (windowSize.height - popupContentSize.height) / 2
        return IntOffset(x, y)
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
