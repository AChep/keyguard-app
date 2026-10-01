package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.usecase.NumberFormatter
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle

/** Locale-aware number formatting with grouping separators, like the JVM implementation. */
class NumberFormatterApple : NumberFormatter {
    private val formatter = LocaleCache { locale ->
        NSNumberFormatter().apply {
            setLocale(locale)
            setNumberStyle(NSNumberFormatterDecimalStyle)
        }
    }

    override fun formatNumber(number: Int): String = formatter.get()
        .stringFromNumber(NSNumber(int = number))
        ?: number.toString()
}
