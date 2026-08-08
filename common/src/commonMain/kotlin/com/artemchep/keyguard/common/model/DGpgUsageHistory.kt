package com.artemchep.keyguard.common.model

import kotlin.time.Clock
import kotlin.time.Instant

data class DGpgUsageHistory(
    val id: String? = null,
    val cipherId: String?,
    val sessionId: String,
    val caller: String?,
    val request: GpgUsageHistoryRequestType,
    val response: GpgUsageHistoryResponseType,
    val fingerprint: String?,
    val keygrip: String?,
    val instant: Instant = Clock.System.now(),
    val eventId: String? = null,
)
