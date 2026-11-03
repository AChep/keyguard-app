package com.artemchep.keyguard.common.model

import kotlin.math.roundToInt

const val UI_SCALE_MIN = 0.5f
const val UI_SCALE_MAX = 1.5f
const val UI_SCALE_DEFAULT = 1f

/**
 * The interface scale moves in 0.05x increments. Steps are kept
 * as integer hundredths, so the values never drift.
 */
private const val HUNDREDTHS = 100
private const val UI_SCALE_MIN_HUNDREDTHS = 50
private const val UI_SCALE_STEP_HUNDREDTHS = 5

/** Index of the last step: 0.5x to 1.5x has 21 steps. */
const val UI_SCALE_MAX_STEP = 20

fun uiScaleToStep(scale: Float): Int {
    val hundredths = (scale * HUNDREDTHS).roundToInt()
    val step = (hundredths - UI_SCALE_MIN_HUNDREDTHS).toFloat() / UI_SCALE_STEP_HUNDREDTHS
    return step.roundToInt().coerceIn(0, UI_SCALE_MAX_STEP)
}

fun uiScaleFromStep(step: Int): Float = uiScaleHundredths(step).toFloat() / HUNDREDTHS

/**
 * Formats a step as `0.5x`, `1.0x`, `1.05x`, `1.25x`.
 */
fun formatUiScale(step: Int): String {
    val hundredths = uiScaleHundredths(step)
    val whole = hundredths / HUNDREDTHS
    val fraction = hundredths % HUNDREDTHS
    val fractionText = if (fraction % 10 == 0) {
        (fraction / 10).toString()
    } else {
        fraction.toString().padStart(2, '0')
    }
    return "$whole.${fractionText}x"
}

private fun uiScaleHundredths(step: Int): Int {
    val s = step.coerceIn(0, UI_SCALE_MAX_STEP)
    return UI_SCALE_MIN_HUNDREDTHS + s * UI_SCALE_STEP_HUNDREDTHS
}
