package com.artemchep.keyguard.util.webdav.internal

import kotlin.time.Instant

internal fun parseHttpDateOrNull(
    value: String,
): Instant? {
    val parts = value
        .trim()
        .split(Regex("\\s+"))
    if (parts.size < 6 || !parts[0].endsWith(",")) {
        return null
    }

    val day = parts[1].toIntOrNull() ?: return null
    val month = monthNumber(parts[2]) ?: return null
    val year = parts[3].toIntOrNull() ?: return null
    val time = parts[4].split(':')
    if (time.size != 3) {
        return null
    }
    val hour = time[0].toIntOrNull() ?: return null
    val minute = time[1].toIntOrNull() ?: return null
    val second = time[2].toIntOrNull() ?: return null
    if (!parts[5].equals("GMT", ignoreCase = true) && !parts[5].equals("UTC", ignoreCase = true)) {
        return null
    }

    if (
        day !in 1..31 ||
        hour !in 0..23 ||
        minute !in 0..59 ||
        second !in 0..60
    ) {
        return null
    }

    val epochSeconds = daysFromCivil(year, month, day) * SECONDS_PER_DAY +
            hour * SECONDS_PER_HOUR +
            minute * SECONDS_PER_MINUTE +
            second
    return Instant.fromEpochSeconds(epochSeconds)
}

private fun monthNumber(
    value: String,
): Int? = when (value.lowercase()) {
    "jan" -> 1
    "feb" -> 2
    "mar" -> 3
    "apr" -> 4
    "may" -> 5
    "jun" -> 6
    "jul" -> 7
    "aug" -> 8
    "sep" -> 9
    "oct" -> 10
    "nov" -> 11
    "dec" -> 12
    else -> null
}

// Howard Hinnant's civil calendar conversion, shifted to Unix epoch days.
private fun daysFromCivil(
    year: Int,
    month: Int,
    day: Int,
): Long {
    var y = year
    y -= if (month <= 2) 1 else 0
    val era = floorDiv(y, 400)
    val yoe = y - era * 400
    val shiftedMonth = month + if (month > 2) -3 else 9
    val doy = (153 * shiftedMonth + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era.toLong() * 146097L + doe.toLong() - 719468L
}

private fun floorDiv(
    value: Int,
    divisor: Int,
): Int {
    var result = value / divisor
    if ((value xor divisor) < 0 && result * divisor != value) {
        result -= 1
    }
    return result
}

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 60L * SECONDS_PER_MINUTE
private const val SECONDS_PER_DAY = 24L * SECONDS_PER_HOUR
