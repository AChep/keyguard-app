package com.artemchep.keyguard.desktop.ui.macos

import androidx.compose.ui.Alignment
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import kotlin.math.roundToInt

internal fun ComposeDialog.applyMacOwnerlessPopupState(
    state: WindowState,
) {
    if (state.size.width.isSpecified && state.size.height.isSpecified) {
        val widthPx = state.size.width.value.roundToInt()
        val heightPx = state.size.height.value.roundToInt()
        if (width != widthPx || height != heightPx) {
            setSize(widthPx, heightPx)
        }
    }

    when (val position = state.position) {
        is WindowPosition.Absolute -> {
            val xPx = position.x.value.roundToInt()
            val yPx = position.y.value.roundToInt()
            if (x != xPx || y != yPx) {
                setLocation(xPx, yPx)
            }
        }
        is WindowPosition.Aligned -> {
            setAlignedMacPopupLocation(position.alignment)
        }
        WindowPosition.PlatformDefault -> {
            setAlignedMacPopupLocation(Alignment.Center)
        }
    }
}

private fun ComposeDialog.setAlignedMacPopupLocation(
    alignment: Alignment,
) {
    val screen = macPopupGraphicsConfiguration().visibleScreenBounds()
    val location = alignment.align(
        size = IntSize(width, height),
        space = IntSize(screen.width, screen.height),
        layoutDirection = LayoutDirection.Ltr,
    )
    setLocation(
        screen.x + location.x,
        screen.y + location.y,
    )
}

private fun ComposeDialog.macPopupGraphicsConfiguration(): GraphicsConfiguration =
    graphicsConfiguration
        ?: GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .defaultScreenDevice
            .defaultConfiguration

private fun GraphicsConfiguration.visibleScreenBounds(): Rectangle {
    val screenBounds = bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(this)
    return Rectangle(
        screenBounds.x + insets.left,
        screenBounds.y + insets.top,
        screenBounds.width - insets.left - insets.right,
        screenBounds.height - insets.top - insets.bottom,
    )
}
