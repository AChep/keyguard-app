package com.artemchep.keyguard.common.service.agent

/** Maximum UTF-16 length for a caller process or application display name. */
internal const val MAX_AGENT_CALLER_NAME_LENGTH = 256

/** Maximum UTF-16 length for a caller executable path. */
internal const val MAX_AGENT_CALLER_EXECUTABLE_PATH_LENGTH = 4 * 1024

/** Maximum UTF-16 length for a caller package, bundle, or application path. */
internal const val MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH = 512

/**
 * Returns whether a Unicode code point is unsafe to render literally in
 * security-sensitive caller identity text.
 */
internal expect fun isUnsafeAgentCallerDisplayCodePoint(codePoint: Int): Boolean

/** Escapes control/directionality characters and bounds caller identity text. */
internal fun String?.sanitizedAgentDisplayValue(maxLength: Int): String? {
    val value =
        this
            ?.takeIf { maxLength > 0 }
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
    val output = StringBuilder(minOf(value.length, maxLength))
    var index = 0
    var lastChunkStart = 0
    var truncated = false
    while (index < value.length) {
        val chunkStart = index
        val first = value[index]
        val hasSurrogatePair =
            first.isHighSurrogate() &&
                value.getOrNull(index + 1)?.isLowSurrogate() == true
        val codePoint =
            if (hasSurrogatePair) {
                val second = value[index + 1]
                0x10000 +
                    ((first.code - HIGH_SURROGATE_START) shl 10) +
                    (second.code - LOW_SURROGATE_START)
            } else {
                first.code
            }
        index += if (hasSurrogatePair) 2 else 1

        val encoded =
            if (isUnsafeAgentCallerDisplayCodePoint(codePoint)) {
                buildString {
                    for (charIndex in chunkStart until index) {
                        append("\\u")
                        append(value[charIndex].code.toString(16).padStart(4, '0'))
                    }
                }
            } else {
                value.substring(chunkStart, index)
            }
        if (output.length + encoded.length > maxLength) {
            truncated = true
            break
        }
        lastChunkStart = output.length
        output.append(encoded)
    }
    if (truncated) {
        if (output.length >= maxLength) {
            // Roll back the complete final scalar/escape rather than splitting
            // a surrogate pair or leaving a partial escape sequence.
            output.setLength(lastChunkStart)
        }
        output.append('…')
    }
    return output.toString().takeIf(String::isNotEmpty)
}

private const val HIGH_SURROGATE_START = 0xD800
private const val LOW_SURROGATE_START = 0xDC00
