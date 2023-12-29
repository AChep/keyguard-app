package com.artemchep.keyguard.common.service.logging

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.platform.util.isRelease

interface LogRepository {
    fun post(
        tag: String,
        message: String,
        level: LogLevel = LogLevel.DEBUG,
    )

    fun add(
        tag: String,
        message: String,
        level: LogLevel = LogLevel.DEBUG,
    ): IO<Any?>
}

/**
 * A version that only exists in debug and gets completely
 * stripped in release builds.
 */
inline fun LogRepository.postDebug(
    tag: String,
    provideMassage: () -> String,
) {
    if (!isRelease) {
        val msg = provideMassage()
        post(tag, msg, level = LogLevel.DEBUG)
    }
}
