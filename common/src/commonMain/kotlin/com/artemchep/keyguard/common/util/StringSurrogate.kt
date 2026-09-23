package com.artemchep.keyguard.common.util

fun String.nextSymbol(index: Int = 0): String {
    if (index !in indices) {
        return ""
    }

    var end = index
    while (true) {
        val a = getOrNull(end)
            ?: break
        if (a.isHighSurrogate() || a.isEmojiControl()) {
            // Take the low surrogate pair too, as it formats
            // human readable symbols.
            end += 1
            continue
        }
        // Check if the next symbol is not zero width space. If
        // it is, then we have to take the symbol after it as well.
        val b = getOrNull(end + 1)
        if (b != null && b.isEmojiControl()) {
            end += 2
            continue
        }
        end += 1
        break
    }
    return substring(index, end)
}

private fun Char.isEmojiControl() =
    this == '\u200d' ||
            this == '\uFE0F'
