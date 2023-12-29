package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.usecase.NumberFormatter
import org.kodein.di.DirectDI
import java.text.NumberFormat

class NumberFormatterJvm(
) : NumberFormatter {
    constructor(directDI: DirectDI) : this()

    override fun formatNumber(number: Int): String {
        val format = NumberFormat.getNumberInstance()
        return format.format(number)
    }
}
