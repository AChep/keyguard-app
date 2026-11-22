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
    /**
     * The name that an MCP client reports for itself, a hint only; for a
     * browser, the name of its pairing when the request was made.
     */
    val clientName: String?,
    val request: CliAgentUsageHistoryRequestType,
    val response: CliAgentUsageHistoryResponseType,
    /** The error code of a [CliAgentUsageHistoryResponseType.FAILURE]. */
    val errorCode: String?,
    /** The command line that `keyguard run` starts. */
    val command: String?,
    /** The working directory of `keyguard run`, or the path of a local `.env` file. */
    val path: String?,
    /** The stable id of the client that made the request: a browser's pairing. */
    val clientId: String?,
    /** What only the client of the request knows; `null` if this build can not read it. */
    val details: CliAgentUsageDetails?,
    val instant: Instant = Clock.System.now(),
    val eventId: String? = null,
)
