package com.artemchep.keyguard.common.service.agent

import platform.Foundation.NSCharacterSet

internal actual fun isUnsafeAgentCallerDisplayCodePoint(codePoint: Int): Boolean {
    if (codePoint !in MIN_UNICODE_CODE_POINT..MAX_UNICODE_CODE_POINT) {
        return true
    }
    val unicodeScalar = codePoint.toUInt()
    return NSCharacterSet.controlCharacterSet.longCharacterIsMember(unicodeScalar) ||
        NSCharacterSet.newlineCharacterSet.longCharacterIsMember(unicodeScalar) ||
        NSCharacterSet.illegalCharacterSet.longCharacterIsMember(unicodeScalar) ||
        codePoint in HIGH_SURROGATE_START..LOW_SURROGATE_END ||
        codePoint in BMP_PRIVATE_USE_START..BMP_PRIVATE_USE_END ||
        codePoint in PLANE_15_PRIVATE_USE_START..PLANE_15_PRIVATE_USE_END ||
        codePoint in PLANE_16_PRIVATE_USE_START..PLANE_16_PRIVATE_USE_END
}

private const val MIN_UNICODE_CODE_POINT = 0
private const val MAX_UNICODE_CODE_POINT = 0x10FFFF
private const val HIGH_SURROGATE_START = 0xD800
private const val LOW_SURROGATE_END = 0xDFFF
private const val BMP_PRIVATE_USE_START = 0xE000
private const val BMP_PRIVATE_USE_END = 0xF8FF
private const val PLANE_15_PRIVATE_USE_START = 0xF0000
private const val PLANE_15_PRIVATE_USE_END = 0xFFFFD
private const val PLANE_16_PRIVATE_USE_START = 0x100000
private const val PLANE_16_PRIVATE_USE_END = 0x10FFFD
