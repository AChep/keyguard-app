package com.artemchep.keyguard.ui.shimmer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Describes the area in which the shimmer effect will be drawn.
 */
internal class ShimmerArea(
    private val widthOfShimmer: Float,
    rotationInDegree: Float,
) {

    private val reducedRotation = rotationInDegree
        .reduceRotation()
        .toRadian()

    // = Rect.Zero -> Don't draw
    // = null -> Draw into view
    private var requestedShimmerBounds: Rect? = null
    private var shimmerSize: Size = Size.Zero

    var translationDistance = 0f
        private set

    var pivotPoint = Offset.Unspecified
        private set

    var shimmerBounds = Rect.Zero
        private set

    var viewBounds = Rect.Zero
        set(value) {
            if (value == field) return
            field = value
            computeShimmerBounds()
        }

    fun updateBounds(shimmerBounds: Rect?) {
        if (this.requestedShimmerBounds == shimmerBounds) return
        requestedShimmerBounds = shimmerBounds
        computeShimmerBounds()
    }

    private fun computeShimmerBounds() {
        if (viewBounds.isEmpty) return
        shimmerBounds = requestedShimmerBounds ?: viewBounds

        // Pivot point in the view's frame of reference
        pivotPoint = -viewBounds.topLeft + shimmerBounds.center

        val newShimmerSize = shimmerBounds.size
        if (shimmerSize != newShimmerSize) {
            shimmerSize = newShimmerSize
            computeTranslationDistance()
        }
    }

    /**
     * Rotating the shimmer results in an effect that will first be visible in one of the corners.
     * It will afterwards travel across the view / display until the last visible part of it will
     * disappear in the opposite corner.
     *
     * A simple shimmer going across the device's screen from left to right has to travel until
     * it reaches the center of the screen and then the same distance again. Without taking the
     * shimmer's own width into account.
     *
     * If the shimmer is now tilted slightly clockwise around the center of the display, a new
     * distance has to be calculated. The required distance is the length of a line, which extends
     * from the top left of the display to the rotated shimmer (or center line), hitting it at a
     * 90 degree angle. As the height and width of the display (or view) are known, the length of
     * the line can be calculated by using basic trigonometric functions.
     */
    private fun computeTranslationDistance() {
        val width = shimmerSize.width / 2
        val height = shimmerSize.height / 2

        val distanceCornerToCenter = sqrt(width.pow(2) + height.pow(2))
        val beta = acos(width / distanceCornerToCenter)
        val alpha = beta - reducedRotation

        val distanceCornerToRotatedCenterLine = cos(alpha) * distanceCornerToCenter
        translationDistance = distanceCornerToRotatedCenterLine * 2 + widthOfShimmer
    }

    /**
     * The formula to compute the required distance for the shimmer's traversal across the view
     * only accepts inputs between 0 and 90. All inputs above 90 can simply be mapped to a value
     * in the accepted range.
     * Inputs between 90 and 180 have to be mapped to the values between 90 and 0 (decreasing).
     * Inputs between 180 and 270 have to be mapped to the values between 0 and 90.
     * And inputs between 270 and 360 have to be mapped to the values between 90 and 0 again.
     */
    private fun Float.reduceRotation(): Float {
        if (this < 0f) {
            throw IllegalArgumentException("The shimmer's rotation must be a positive number")
        }
        var rotation = this % 180 // 0..179, 0
        rotation -= 90 // -90..0..89, -90
        rotation = -abs(rotation) // -90..0..-90
        return rotation + 90 // 0..90..0
    }

    private fun Float.toRadian(): Float = this / 180 * PI.toFloat()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ShimmerArea

        if (widthOfShimmer != other.widthOfShimmer) return false
        if (reducedRotation != other.reducedRotation) return false

        return true
    }

    override fun hashCode(): Int {
        var result = widthOfShimmer.hashCode()
        result = 31 * result + reducedRotation.hashCode()
        return result
    }
}
