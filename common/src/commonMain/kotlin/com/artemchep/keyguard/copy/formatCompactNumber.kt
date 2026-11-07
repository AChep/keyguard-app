package com.artemchep.keyguard.copy

import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.number_compact_billion
import com.artemchep.keyguard.res.number_compact_million
import com.artemchep.keyguard.res.number_compact_quadrillion
import com.artemchep.keyguard.res.number_compact_quintillion
import com.artemchep.keyguard.res.number_compact_thousand
import com.artemchep.keyguard.res.number_compact_trillion
import org.jetbrains.compose.resources.getString
import kotlin.math.abs

private const val COMPACT_UNIT_BASE = 1000L
private const val MAX_UNSCALED_COUNT = COMPACT_UNIT_BASE - 1L
private const val COMPACT_PRECISION = 10L

private val compactNumberUnits = listOf(
    Res.string.number_compact_thousand,
    Res.string.number_compact_million,
    Res.string.number_compact_billion,
    Res.string.number_compact_trillion,
    Res.string.number_compact_quadrillion,
    Res.string.number_compact_quintillion,
)

internal suspend fun formatCompactNumber(
    number: Long,
    formatDecimal: (Double) -> String,
): String {
    if (number in -MAX_UNSCALED_COUNT..MAX_UNSCALED_COUNT) {
        return formatDecimal(number.toDouble())
    }

    var divisor = COMPACT_UNIT_BASE
    var unitIndex = 0
    while (unitIndex < compactNumberUnits.lastIndex) {
        val whole = number / divisor
        if (whole in -MAX_UNSCALED_COUNT..MAX_UNSCALED_COUNT) break
        divisor *= COMPACT_UNIT_BASE
        unitIndex += 1
    }

    var tenths = number.roundedTenths(divisor)
    if (abs(tenths) >= COMPACT_UNIT_BASE * COMPACT_PRECISION && unitIndex < compactNumberUnits.lastIndex) {
        divisor *= COMPACT_UNIT_BASE
        unitIndex += 1
        tenths = number.roundedTenths(divisor)
    }

    return getString(compactNumberUnits[unitIndex], formatDecimal(tenths / COMPACT_PRECISION.toDouble()))
}

/** Round before converting to Double, without overflowing even for Long.MIN_VALUE. */
private fun Long.roundedTenths(divisor: Long): Long {
    val step = divisor / COMPACT_PRECISION
    val whole = this / step
    val remainder = this % step
    return whole + when {
        remainder >= step / 2L -> 1L
        remainder <= -step / 2L -> -1L
        else -> 0L
    }
}
