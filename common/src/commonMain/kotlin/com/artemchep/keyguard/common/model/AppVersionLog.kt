package com.artemchep.keyguard.common.model

import kotlin.time.Instant

data class AppVersionLog(
    val version: String,
    val ref: String,
    val timestamp: Instant,
)
