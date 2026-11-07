package com.artemchep.keyguard.common.usecase

interface NumberFormatter {
    fun formatNumber(
        number: Int,
    ): String

    /** Formats a count using localized decimal digits and compact units, such as 1.5K. */
    suspend fun formatCompactNumber(
        number: Long,
    ): String
}
