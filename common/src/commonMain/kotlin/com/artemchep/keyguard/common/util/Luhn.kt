package com.artemchep.keyguard.common.util

// https://en.wikipedia.org/wiki/Luhn_algorithm
fun validLuhn(number: String): Boolean {
    if (number.isEmpty()) {
        return true
    }

    val parity = number.length.rem(2)
    val checksum = number
        .foldIndexed(0) { index, y, x ->
            val n = charToDigit(x)
            y + when {
                index.rem(2) != parity -> n
                n > 4 -> 2 * n - 9
                else -> 2 * n
            }
        }
    return checksum.rem(10) == 0
}

private fun charToDigit(char: Char) = char - '0'
