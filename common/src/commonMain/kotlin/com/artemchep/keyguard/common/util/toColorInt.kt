package com.artemchep.keyguard.common.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

fun Color.toHex(): String {
    val argb = toArgb()
    val red = argb.shr(16).and(0xFF)
    val green = argb.shr(8).and(0xFF)
    val blue = argb.and(0xFF)
    return buildString {
        append('#')
        append(red.toHex())
        append(green.toHex())
        append(blue.toHex())
    }
}

private fun Int.toHex() = toString(16).uppercase().padStart(2, '0')

fun String.toColorInt() = this
    .trimStart('#')
    // Add the alpha component of the
    // color
    .padStart(8, 'F')
    .toLong(16)
    .toInt()

fun Color.hue(): Float {
    val hsl = FloatArray(3)
    RGBToHSL(
        rf = red,
        gf = green,
        bf = blue,
        outHsl = hsl,
    )
    return hsl[0]
}

/**
 * Convert RGB components to HSL (hue-saturation-lightness).
 *  * outHsl[0] is Hue [0, 360)
 *  * outHsl[1] is Saturation [0, 1]
 *  * outHsl[2] is Lightness [0, 1]
 *
 * @param rf red component value [0, 1]
 * @param gf green component value [0, 1]
 * @param bf blue component value [0, 1]
 * @param outHsl 3-element array which holds the resulting HSL components
 */
fun RGBToHSL(
    rf: Float,
    gf: Float,
    bf: Float,
    outHsl: FloatArray,
) {
    val max =
        max(rf.toDouble(), max(gf.toDouble(), bf.toDouble())).toFloat()
    val min =
        min(rf.toDouble(), min(gf.toDouble(), bf.toDouble())).toFloat()
    val deltaMaxMin = max - min
    var h: Float
    val s: Float
    val l = (max + min) / 2f
    if (max == min) {
        // Monochromatic
        s = 0f
        h = s
    } else {
        h = if (max == rf) {
            (gf - bf) / deltaMaxMin % 6f
        } else if (max == gf) {
            (bf - rf) / deltaMaxMin + 2f
        } else {
            (rf - gf) / deltaMaxMin + 4f
        }
        s = (deltaMaxMin / (1f - abs((2f * l - 1f).toDouble()))).toFloat()
    }
    h = h * 60f % 360f
    if (h < 0) {
        h += 360f
    }
    outHsl[0] = constrain(h, 0f, 360f)
    outHsl[1] = constrain(s, 0f, 1f)
    outHsl[2] = constrain(l, 0f, 1f)
}

private fun constrain(amount: Float, low: Float, high: Float): Float {
    return if (amount < low) low else min(amount.toDouble(), high.toDouble()).toFloat()
}
