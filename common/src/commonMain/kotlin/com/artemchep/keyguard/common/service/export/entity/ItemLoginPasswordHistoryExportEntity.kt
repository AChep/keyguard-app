package com.artemchep.keyguard.common.service.export.entity

import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ItemLoginPasswordHistoryExportEntity(
    val lastUsedDate: Instant?,
    val password: String,
)
