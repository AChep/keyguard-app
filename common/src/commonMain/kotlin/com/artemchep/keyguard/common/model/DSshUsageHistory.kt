package com.artemchep.keyguard.common.model

import kotlin.time.Clock
import kotlin.time.Instant

data class DSshUsageHistory(
    val id: String? = null,
    val cipherId: String?,
    val sessionId: String,
    val caller: String?,
    val request: SshUsageHistoryRequestType,
    val response: SshUsageHistoryResponseType,
    val fingerprint: String?,
    val instant: Instant = Clock.System.now(),
)
