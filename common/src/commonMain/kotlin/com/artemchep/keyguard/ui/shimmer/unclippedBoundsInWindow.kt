package com.artemchep.keyguard.ui.shimmer

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow

fun LayoutCoordinates.unclippedBoundsInWindow(): Rect {
    val positionInWindow = positionInWindow()
    return Rect(
        positionInWindow.x,
        positionInWindow.y,
        positionInWindow.x + size.width,
        positionInWindow.y + size.height,
    )
}
