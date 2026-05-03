package com.artemchep.keyguard.ui.shimmer

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.debugInspectorInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

fun Modifier.shimmer(
    customShimmer: Shimmer? = null,
): Modifier = composed(
    factory = {
        val shimmer = customShimmer ?: rememberShimmer(ShimmerBounds.View)

        val width = with(LocalDensity.current) { shimmer.theme.shimmerWidth.toPx() }
        ShimmerModifierElement(
            customShimmer = customShimmer,
            shimmer = shimmer,
            widthOfShimmer = width,
            rotation = shimmer.theme.rotation,
        )
    },
    inspectorInfo = debugInspectorInfo {
        name = "shimmer"
        properties["customShimmer"] = customShimmer
    },
)

private class ShimmerModifierElement(
    private val customShimmer: Shimmer?,
    private val shimmer: Shimmer,
    private val widthOfShimmer: Float,
    private val rotation: Float,
) : ModifierNodeElement<ShimmerModifierNode>() {
    override fun create(): ShimmerModifierNode =
        ShimmerModifierNode(
            shimmer = shimmer,
            widthOfShimmer = widthOfShimmer,
            rotation = rotation,
        )

    override fun update(node: ShimmerModifierNode) {
        node.update(
            shimmer = shimmer,
            widthOfShimmer = widthOfShimmer,
            rotation = rotation,
        )
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "shimmer"
        properties["customShimmer"] = customShimmer
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ShimmerModifierElement) return false

        if (customShimmer !== other.customShimmer) return false
        if (shimmer !== other.shimmer) return false
        if (widthOfShimmer != other.widthOfShimmer) return false
        if (rotation != other.rotation) return false

        return true
    }

    override fun hashCode(): Int {
        var result = customShimmer.hashCode()
        result = 31 * result + shimmer.hashCode()
        result = 31 * result + widthOfShimmer.hashCode()
        result = 31 * result + rotation.hashCode()
        return result
    }
}

private class ShimmerModifierNode(
    private var shimmer: Shimmer,
    private var widthOfShimmer: Float,
    private var rotation: Float,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    private var area = ShimmerArea(widthOfShimmer, rotation)
    private var boundsJob: Job? = null

    override fun onAttach() {
        restartBoundsCollection()
    }

    override fun onDetach() {
        boundsJob?.cancel()
        boundsJob = null
    }

    override fun onReset() {
        boundsJob?.cancel()
        boundsJob = null
        area = ShimmerArea(widthOfShimmer, rotation)
    }

    override fun ContentDrawScope.draw() {
        with(shimmer.effect) { draw(area) }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val viewBounds = coordinates.unclippedBoundsInWindow()
        if (area.viewBounds == viewBounds) return
        area.viewBounds = viewBounds
        invalidateDraw()
    }

    fun update(
        shimmer: Shimmer,
        widthOfShimmer: Float,
        rotation: Float,
    ) {
        val areaChanged = this.widthOfShimmer != widthOfShimmer || this.rotation != rotation
        if (areaChanged) {
            this.widthOfShimmer = widthOfShimmer
            this.rotation = rotation
            val viewBounds = area.viewBounds
            area = ShimmerArea(widthOfShimmer, rotation).also {
                it.viewBounds = viewBounds
            }
        }

        val shimmerChanged = this.shimmer !== shimmer
        this.shimmer = shimmer
        if (shimmerChanged || areaChanged) {
            area.updateBounds(shimmer.boundsFlow.value)
            if (isAttached) {
                restartBoundsCollection()
            }
        }
        invalidateDraw()
    }

    private fun restartBoundsCollection() {
        boundsJob?.cancel()
        area.updateBounds(shimmer.boundsFlow.value)
        boundsJob = coroutineScope.launch {
            shimmer.boundsFlow.collect {
                area.updateBounds(it)
                invalidateDraw()
            }
        }
    }
}
