/*
 * Dr. Ing. h.c. F. Porsche AG confidential. This code is protected by intellectual property rights.
 * The Dr. Ing. h.c. F. Porsche AG owns exclusive legal rights of use.
 */
package com.artemchep.keyguard.ui.shimmer

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.DrawModifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.OnGloballyPositionedModifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.debugInspectorInfo

fun Modifier.shimmer(
    customShimmer: Shimmer? = null,
): Modifier = composed(
    factory = {
        val shimmer = customShimmer ?: rememberShimmer(ShimmerBounds.View)

        val width = with(LocalDensity.current) { shimmer.theme.shimmerWidth.toPx() }
        val area = remember(width, shimmer.theme.rotation) {
            ShimmerArea(width, shimmer.theme.rotation)
        }

        LaunchedEffect(area, shimmer) {
            shimmer.boundsFlow.collect {
                area.updateBounds(it)
            }
        }

        remember(area, shimmer) { ShimmerModifier(area, shimmer.effect) }
    },
    inspectorInfo = debugInspectorInfo {
        name = "shimmer"
        properties["customShimmer"] = customShimmer
    },
)

internal class ShimmerModifier(
    private val area: ShimmerArea,
    private val effect: ShimmerEffect,
) : DrawModifier, OnGloballyPositionedModifier {

    override fun ContentDrawScope.draw() {
        with(effect) { draw(area) }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val viewBounds = coordinates.unclippedBoundsInWindow()
        area.viewBounds = viewBounds
    }
}
