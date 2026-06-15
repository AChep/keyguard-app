package com.artemchep.keyguard.common.util

import kotlin.streams.asSequence

actual fun String.asCodePointsSequence(): Sequence<String> = codePoints()
    .asSequence()
    .map { codePoint ->
        String(Character.toChars(codePoint))
    }
