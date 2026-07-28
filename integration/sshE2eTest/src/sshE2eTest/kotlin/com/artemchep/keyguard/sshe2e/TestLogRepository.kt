package com.artemchep.keyguard.sshe2e

import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository

class TestLogRepository : LogRepository {
    override fun post(
        tag: String,
        message: String,
        level: LogLevel,
    ) {
        println("[${level.letter}] $tag: $message")
    }

    override suspend fun add(
        tag: String,
        message: String,
        level: LogLevel,
    ) {
        post(tag, message, level)
    }
}
