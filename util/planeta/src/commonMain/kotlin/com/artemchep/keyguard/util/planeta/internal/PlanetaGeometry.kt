package com.artemchep.keyguard.util.planeta.internal

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal fun Float.toRadians(): Float =
    this / 180f * PI.toFloat()

internal fun Offset.rotateDegrees(degrees: Float): Offset {
    if (abs(degrees) < 0.001f) return this
    val radians = degrees.toRadians()
    val cos = cos(radians)
    val sin = sin(radians)
    return Offset(
        x = x * cos - y * sin,
        y = x * sin + y * cos,
    )
}

internal const val TwoPi = 6.2831855f
