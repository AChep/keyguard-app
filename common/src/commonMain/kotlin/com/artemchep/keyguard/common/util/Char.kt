package com.artemchep.keyguard.common.util

internal fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

internal fun Char.isLowerHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f'
