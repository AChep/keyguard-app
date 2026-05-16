package com.artemchep.keyguard.feature.filepicker

import kotlin.math.abs
import kotlin.math.roundToLong

fun humanReadableByteCountBin(bytes: Long): String =
    humanReadableByteCount(
        bytes = bytes,
        unit = 1024L,
        units = "KMGTPE",
        unitSuffix = "iB",
    )

fun humanReadableByteCountSI(bytes: Long): String =
    humanReadableByteCount(
        bytes = bytes,
        unit = 1000L,
        units = "kMGTPE",
        unitSuffix = "B",
    )

private fun humanReadableByteCount(
    bytes: Long,
    unit: Long,
    units: String,
    unitSuffix: String,
): String {
    val absoluteBytes = bytes.absoluteValueWithoutOverflow()
    if (absoluteBytes < unit) {
        return "$bytes B"
    }

    var divider = unit
    var unitIndex = 0
    while (
        unitIndex < units.lastIndex &&
        absoluteBytes.roundedTenths(divider) >= unit * 10L
    ) {
        divider *= unit
        unitIndex += 1
    }

    return "${formatOneDecimal(bytes / divider.toDouble())} ${units[unitIndex]}$unitSuffix"
}

private fun Long.absoluteValueWithoutOverflow(): Long =
    if (this == Long.MIN_VALUE) Long.MAX_VALUE else abs(this)

private fun Long.roundedTenths(divider: Long): Long {
    val whole = this / divider
    val remainder = this % divider
    return whole * 10L + (remainder * 10L + divider / 2L) / divider
}

private fun formatOneDecimal(value: Double): String {
    val scaled = (value * 10.0).roundToLong()
    val sign = if (scaled < 0) "-" else ""
    val absScaled = abs(scaled)
    return "$sign${absScaled / 10}.${absScaled % 10}"
}
