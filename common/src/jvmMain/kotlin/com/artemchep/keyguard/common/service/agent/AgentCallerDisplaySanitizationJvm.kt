package com.artemchep.keyguard.common.service.agent

internal actual fun isUnsafeAgentCallerDisplayCodePoint(codePoint: Int): Boolean {
    val category = Character.getType(codePoint)
    return Character.isISOControl(codePoint) ||
        category == Character.FORMAT.toInt() ||
        category == Character.LINE_SEPARATOR.toInt() ||
        category == Character.PARAGRAPH_SEPARATOR.toInt() ||
        category == Character.SURROGATE.toInt() ||
        category == Character.PRIVATE_USE.toInt() ||
        category == Character.UNASSIGNED.toInt()
}
