@file:OptIn(ExperimentalForeignApi::class)

package com.artemchep.keyguard.feature.home.vault.search.sort

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.CFLocaleCopyCurrent
import platform.CoreFoundation.CFRangeMake
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCompareWithOptionsAndLocale
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.kCFCompareCaseInsensitive
import platform.CoreFoundation.kCFCompareDiacriticInsensitive
import platform.CoreFoundation.kCFCompareLocalized
import platform.CoreFoundation.kCFCompareWidthInsensitive
import platform.CoreFoundation.kCFStringEncodingUTF8

internal actual fun compareAlphabetically(a: String, b: String): Int {
    val aString = CFStringCreateWithCString(null, a, kCFStringEncodingUTF8)
        ?: return a.compareTo(b, ignoreCase = true).toComparison()
    val bString = CFStringCreateWithCString(null, b, kCFStringEncodingUTF8)
        ?: run {
            CFRelease(aString)
            return a.compareTo(b, ignoreCase = true).toComparison()
        }
    val locale = CFLocaleCopyCurrent()
    val compareOptions = kCFCompareCaseInsensitive or
            kCFCompareDiacriticInsensitive or
            kCFCompareWidthInsensitive or
            kCFCompareLocalized

    return try {
        CFStringCompareWithOptionsAndLocale(
            theString1 = aString,
            theString2 = bString,
            rangeToCompare = CFRangeMake(0, CFStringGetLength(aString)),
            compareOptions = compareOptions,
            locale = locale,
        ).toComparison()
    } finally {
        CFRelease(locale)
        CFRelease(bString)
        CFRelease(aString)
    }
}

private fun Long.toComparison(): Int = when {
    this < 0L -> -1
    this > 0L -> 1
    else -> 0
}

private fun Int.toComparison(): Int = when {
    this < 0 -> -1
    this > 0 -> 1
    else -> 0
}
