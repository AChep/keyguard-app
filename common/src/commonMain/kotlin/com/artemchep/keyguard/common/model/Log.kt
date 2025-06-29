package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.common.service.logging.LogLevel
import kotlin.time.Instant

data class Log(
    val tag: String,
    val message: String,
    val level: LogLevel,
    val createdAt: Instant,
)
