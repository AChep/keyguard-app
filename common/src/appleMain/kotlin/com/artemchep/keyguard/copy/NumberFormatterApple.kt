package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.usecase.NumberFormatter

class NumberFormatterApple : NumberFormatter {

    override fun formatNumber(number: Int): String = number.toString()
}
