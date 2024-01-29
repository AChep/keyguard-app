package com.artemchep.keyguard.common.model

import kotlinx.datetime.Instant

data class AppVersionLog(
    val version: String,
    val ref: String,
    val timestamp: Instant,
)
