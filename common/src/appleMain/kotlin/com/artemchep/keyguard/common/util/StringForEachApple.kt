package com.artemchep.keyguard.common.util

actual fun String.asCodePointsSequence(): Sequence<String> = sequence {
    var index = 0
    while (index < length) {
        val char = this@asCodePointsSequence[index]
        val nextIndex = if (char.isHighSurrogate()) {
            val next = getOrNull(index + 1)
            if (next != null && next.isLowSurrogate()) {
                index + 2
            } else {
                index + 1
            }
        } else {
            index + 1
        }
        yield(substring(index, nextIndex))
        index = nextIndex
    }
}
