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
internal fun isUnsafeAgentCallerDisplayCodePoint(codePoint: Int): Boolean {
    val category = Character.getType(codePoint)
    return Character.isISOControl(codePoint) ||
        category == Character.FORMAT.toInt() ||
        category == Character.SURROGATE.toInt() ||
        category == Character.PRIVATE_USE.toInt() ||
        category == Character.UNASSIGNED.toInt()
}
