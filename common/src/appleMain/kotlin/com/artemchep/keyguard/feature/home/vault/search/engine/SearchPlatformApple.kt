@file:OptIn(ExperimentalForeignApi::class)

package com.artemchep.keyguard.feature.home.vault.search.engine

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import platform.CoreFoundation.CFRangeMake
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateMutableCopy
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringGetCString
import platform.CoreFoundation.CFStringGetCStringPtr
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFStringGetMaximumSizeForEncoding
import platform.CoreFoundation.CFStringGetRangeOfComposedCharactersAtIndex
import platform.CoreFoundation.CFStringNormalize
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFStringTokenizerAdvanceToNextToken
import platform.CoreFoundation.CFStringTokenizerCreate
import platform.CoreFoundation.CFStringTokenizerGetCurrentTokenRange
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFStringNormalizationFormKC
import platform.CoreFoundation.kCFStringNormalizationFormKD
import platform.CoreFoundation.kCFStringTokenizerTokenNone
import platform.CoreFoundation.kCFStringTokenizerUnitWord

internal actual fun searchCompatibilityNormalize(value: String): String =
    value.normalize(kCFStringNormalizationFormKC)

internal actual fun searchDecomposeNormalize(value: String): String =
    value.normalize(kCFStringNormalizationFormKD)

internal actual fun requiresPlatformWordSegmentation(value: String): Boolean =
    value.any(Char::requiresPlatformWordSegmentation)

internal actual fun platformWordSegments(value: String): List<String> {
    if (value.isEmpty()) {
        return emptyList()
    }

    val tokens = mutableListOf<String>()
    value.withCFString { cfString ->
        val tokenizer = CFStringTokenizerCreate(
            alloc = null,
            string = cfString,
            range = CFRangeMake(0, CFStringGetLength(cfString)),
            options = kCFStringTokenizerUnitWord,
            locale = null,
        )
        try {
            while (CFStringTokenizerAdvanceToNextToken(tokenizer) != kCFStringTokenizerTokenNone) {
                val range = CFStringTokenizerGetCurrentTokenRange(tokenizer)
                range.useContents {
                    val start = location.toInt()
                    val end = start + length.toInt()
                    val token = value.substring(start, end)
                    if (token.any(Char::isLetterOrDigit)) {
                        tokens += token
                    }
                }
            }
        } finally {
            CFRelease(tokenizer)
        }
    }
    return tokens
}

internal actual fun forEachPlatformGraphemeCluster(
    value: String,
    block: (startIndex: Int, endIndex: Int) -> Unit,
) {
    if (value.isEmpty()) {
        return
    }

    value.withCFString { cfString ->
        val valueLength = CFStringGetLength(cfString)
        var index = 0L
        while (index < valueLength) {
            val range = CFStringGetRangeOfComposedCharactersAtIndex(cfString, index)
            range.useContents {
                val start = location
                val end = start + length
                if (end <= index) {
                    index++
                } else {
                    block(start.toInt(), end.toInt())
                    index = end
                }
            }
        }
    }
}

private fun Char.requiresPlatformWordSegmentation(): Boolean =
    this in '\u0E00'..'\u0E7F' || // Thai
        this in '\u1100'..'\u11FF' || // Hangul Jamo
        this in '\u3040'..'\u309F' || // Hiragana
        this in '\u30A0'..'\u30FF' || // Katakana
        this in '\u31F0'..'\u31FF' || // Katakana Phonetic Extensions
        this in '\u3130'..'\u318F' || // Hangul Compatibility Jamo
        this in '\u3400'..'\u4DBF' || // CJK Unified Ideographs Extension A
        this in '\u4E00'..'\u9FFF' || // CJK Unified Ideographs
        this in '\uAC00'..'\uD7AF' || // Hangul Syllables
        this in '\uF900'..'\uFAFF' // CJK Compatibility Ideographs

private fun String.normalize(form: Long): String {
    val source = createCFString() ?: return this
    val normalized = CFStringCreateMutableCopy(
        alloc = null,
        maxLength = 0,
        theString = source,
    )
    CFRelease(source)
    if (normalized == null) {
        return this
    }

    return try {
        CFStringNormalize(normalized, form)
        normalized.toKString(fallback = this)
    } finally {
        CFRelease(normalized)
    }
}

private inline fun <T> String.withCFString(
    block: (CFStringRef) -> T,
): T? {
    val cfString = createCFString() ?: return null
    return try {
        block(cfString)
    } finally {
        CFRelease(cfString)
    }
}

private fun String.createCFString(): CFStringRef? =
    CFStringCreateWithCString(
        alloc = null,
        cStr = this,
        encoding = kCFStringEncodingUTF8,
    )

private fun CFStringRef.toKString(
    fallback: String,
): String {
    CFStringGetCStringPtr(this, kCFStringEncodingUTF8)
        ?.let { return it.toKString() }

    val bufferSize = CFStringGetMaximumSizeForEncoding(
        length = CFStringGetLength(this),
        encoding = kCFStringEncodingUTF8,
    ) + 1
    if (bufferSize <= 0) {
        return fallback
    }

    return memScoped {
        val buffer = allocArray<ByteVar>(bufferSize.toInt())
        val success = CFStringGetCString(
            theString = this@toKString,
            buffer = buffer,
            bufferSize = bufferSize,
            encoding = kCFStringEncodingUTF8,
        )
        if (success) buffer.toKString() else fallback
    }
}
