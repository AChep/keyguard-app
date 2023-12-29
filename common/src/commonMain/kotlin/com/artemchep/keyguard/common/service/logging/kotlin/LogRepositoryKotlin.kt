package com.artemchep.keyguard.common.service.logging.kotlin

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import kotlinx.coroutines.GlobalScope

class LogRepositoryKotlin : LogRepository {
    override fun post(
        tag: String,
        message: String,
        level: LogLevel,
    ) {
        add(tag, message, level).attempt().launchIn(GlobalScope)
    }

    override fun add(
        tag: String,
        message: String,
        level: LogLevel,
    ) = ioEffect {
        println("$tag: $message")
    }
}
