package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.usecase.NumberFormatter
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle

/**
 * Locale-aware number formatting, matching [com.artemchep.keyguard.copy.NumberFormatterJvm]'s
 * `NumberFormat.getNumberInstance()` — grouping separators included, so large counts read
 * as "1,234" / "1 234" / "1.234" the way they do on the other platforms rather than as a
 * bare digit run.
 */
class NumberFormatterApple : NumberFormatter {

    override fun formatNumber(number: Int): String {
        val formatter = NSNumberFormatter().apply {
            setNumberStyle(NSNumberFormatterDecimalStyle)
        }
        return formatter.stringFromNumber(NSNumber(int = number))
            ?: number.toString()
    }
}
