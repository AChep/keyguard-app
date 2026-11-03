package com.artemchep.keyguard.common.model

import kotlin.time.Clock
import kotlin.time.Instant

data class AddCliAgentUsageHistoryRequest(
    val cipherId: String?,
    val sessionId: String,
    val client: CliAgentUsageHistoryClient,
    val caller: String?,
    val clientName: String?,
    val request: CliAgentUsageHistoryRequestType,
    val response: CliAgentUsageHistoryResponseType,
    val errorCode: String?,
    val command: String?,
    val path: String?,
    val instant: Instant = Clock.System.now(),
    /**
     * Optional unique event id. Queued events set it so that replaying
     * the queue remains idempotent.
     */
    val eventId: String? = null,
)
