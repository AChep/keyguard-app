package com.artemchep.keyguard.common.service.logging.kotlin

import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepositoryChild

class LogRepositoryKotlin : LogRepositoryChild {
    override suspend fun add(
        tag: String,
        message: String,
        level: LogLevel,
    ) {
        println("[${level.letter}]/$tag: $message")
    }
}
