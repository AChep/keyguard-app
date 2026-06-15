package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.usecase.NumberFormatter
import org.kodein.di.DirectDI

class NumberFormatterIos(
) : NumberFormatter {
    constructor(directDI: DirectDI) : this()

    override fun formatNumber(number: Int): String = number.toString()
}
