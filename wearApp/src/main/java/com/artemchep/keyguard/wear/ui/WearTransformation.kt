package com.artemchep.keyguard.wear.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.wear.compose.material3.SurfaceTransformation

fun Modifier.surfaceTransformation(
    transformation: SurfaceTransformation?,
) = this
    .graphicsLayer {
        val transformation = transformation
            ?: return@graphicsLayer
        with(transformation) {
            applyContainerTransformation()
        }
    }
