package com.artemchep.keyguard.common.service.logging

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

interface LogRepositoryBase {
    fun post(
        tag: String,
        message: String,
        level: LogLevel = LogLevel.DEBUG,
    ) {
        GlobalScope.launch {
            runCatching {
                add(tag, message, level)
            }
        }
    }

    suspend fun add(
        tag: String,
        message: String,
        level: LogLevel = LogLevel.DEBUG,
    )
}
