package com.artemchep.keyguard.common.util

fun String.nextSymbol(index: Int = 0): String {
    if (index !in indices) {
        return ""
    }

    var end = index
    while (end < length) {
        val a = this[end]
        if (a.isHighSurrogate() || a.isEmojiControl()) {
            // Take the low surrogate pair too, as it formats
            // human readable symbols.
            end += 1
        } else if (getOrNull(end + 1)?.isEmojiControl() == true) {
            // Include the control character and the symbol after it as well.
            end += 2
        } else {
            end += 1
            break
        }
    }
    return substring(index, end)
}

private fun Char.isEmojiControl() =
    this == '\u200d' ||
            this == '\uFE0F'
