package com.artemchep.keyguard.common.model

import kotlin.time.Instant

data class DHibpC(
    val leaks: List<DHibp>,
)

data class DHibp(
    val title: String,
    val name: String,
    val website: String,
    val icon: String,
    val description: String,
    val count: Int?,
    val occurredAt: Instant?,
    val reportedAt: Instant?,
    val dataClasses: List<String>,
)
