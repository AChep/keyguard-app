package com.artemchep.keyguard.common.util

/**
 * This file name without its extension. The extension starts at the
 * last dot, unless that dot begins the name: `.env` has none.
 */
internal fun String.fileNameStem(): String {
    val dot = lastIndexOf('.')
    return if (dot > 0) substring(0, dot) else this
}
