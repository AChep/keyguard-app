package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.usecase.NumberFormatter
import java.text.NumberFormat
import java.util.Locale

class NumberFormatterJvm(
    private val localeProvider: () -> Locale = { Locale.getDefault(Locale.Category.FORMAT) },
) : NumberFormatter {

    override fun formatNumber(number: Int): String {
        val format = NumberFormat.getNumberInstance(localeProvider())
        return format.format(number)
    }

    override suspend fun formatCompactNumber(number: Long): String {
        val format = NumberFormat.getNumberInstance(localeProvider()).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 1
        }
        return formatCompactNumber(number) { format.format(it) }
    }
}
