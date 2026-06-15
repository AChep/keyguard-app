package com.artemchep.keyguard.common.model

import kotlin.time.Instant

data class DBarcodeUsageHistory(
    val id: String,
    val type: String,
    val createdAt: Instant,
)
