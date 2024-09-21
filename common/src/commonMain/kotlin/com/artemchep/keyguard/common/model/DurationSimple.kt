package com.artemchep.keyguard.common.model

data class DurationSimple(
    val years: Int,
    val months: Int,
    val weeks: Int,
    val days: Int,
) {
    companion object {
        // The implementation assumes that there's no
        // time provided in the period.
        // https://www.digi.com/resources/documentation/digidocs/90001488-13/reference/r_iso_8601_duration_format.htm
        fun parse(iso8601: String): DurationSimple {
            fun extractAsInt(regex: Regex): Int {
                val result = regex.find(iso8601)
                    ?: return 0
                return result.groupValues.getOrNull(1)
                    ?.toIntOrNull()
                    ?: 0
            }

            return DurationSimple(
                years = extractAsInt("(\\d+)Y".toRegex()),
                months = extractAsInt("(\\d+)M".toRegex()),
                weeks = extractAsInt("(\\d+)W".toRegex()),
                days = extractAsInt("(\\d+)D".toRegex()),
            )
        }
    }
}
