package com.artemchep.keyguard.common.model

import kotlin.time.Clock
import kotlin.time.Instant

data class DCliAgentUsageHistory(
    val id: String? = null,
    /** The environment that the request reached. */
    val cipherId: String?,
    val sessionId: String,
    val client: CliAgentUsageHistoryClient,
    val caller: String?,
    /** The name that an MCP client reports for itself; a hint only. */
    val clientName: String?,
    val request: CliAgentUsageHistoryRequestType,
    val response: CliAgentUsageHistoryResponseType,
    /** The error code of a [CliAgentUsageHistoryResponseType.FAILURE]. */
    val errorCode: String?,
    /** The command line that `keyguard run` starts. */
    val command: String?,
    /** The working directory of `keyguard run`, or the path of a local `.env` file. */
    val path: String?,
    val instant: Instant = Clock.System.now(),
    val eventId: String? = null,
)
