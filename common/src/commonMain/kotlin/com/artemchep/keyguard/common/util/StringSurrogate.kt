package com.artemchep.keyguard.common.util

fun String.nextSymbol(index: Int = 0): String {
    val a = getOrNull(index)
        ?: return ""
    if (a.isHighSurrogate() || a.isEmojiControl()) {
        // Take the low surrogate pair too, as it formats
        // human readable symbols.
        return a.toString() + nextSymbol(index + 1)
    }
    // Check if the next symbol is not zero width space. If
    // it is, then we have to take the symbol after it as well.
    val b = getOrNull(index + 1)
    if (b != null && b.isEmojiControl()) {
        return a.toString() + b + nextSymbol(index + 2)
    }
    return a.toString()
}

private fun Char.isEmojiControl() =
    this == '\u200d' ||
            this == '\uFE0F'
