package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.usecase.NumberFormatter
import java.text.NumberFormat

class NumberFormatterJvm : NumberFormatter {

    override fun formatNumber(number: Int): String {
        val format = NumberFormat.getNumberInstance()
        return format.format(number)
    }
}
