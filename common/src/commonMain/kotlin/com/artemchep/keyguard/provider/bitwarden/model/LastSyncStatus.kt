package com.artemchep.keyguard.provider.bitwarden.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class LastSyncStatus(
    val accountId: String,
    val timestamp: Instant,
)
