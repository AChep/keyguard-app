package com.artemchep.keyguard.common.model

import kotlin.time.Clock
import kotlin.time.Instant

data class AddGpgUsageHistoryRequest(
    val cipherId: String?,
    val sessionId: String,
    val caller: String?,
    val request: GpgUsageHistoryRequestType,
    val response: GpgUsageHistoryResponseType,
    val fingerprint: String?,
    val keygrip: String?,
    val instant: Instant = Clock.System.now(),
    /**
     * Optional unique event id. Queued events and Android IPC direct writes
     * set it so falling back to or replaying the queue remains idempotent.
     */
    val eventId: String? = null,
)
