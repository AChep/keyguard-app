package com.artemchep.keyguard.common.service.pendinghistory

import kotlinx.serialization.Serializable

/**
 * The sensitive part of a [PendingUsageHistory] row; serialized to JSON
 * and sealed with [PendingUsageHistoryEnvelope] before it is written to
 * the exposed database.
 */
@Serializable
data class PendingUsageHistoryPayload(
    val protocol: String,
    val sessionId: String,
    val caller: String? = null,
    val requestType: String,
    val responseType: String,
    val cipherId: String? = null,
    val fingerprint: String? = null,
    val keygrip: String? = null,
    val cliAgent: PendingCliAgentUsageHistory? = null,
)

/** The fields of a CLI agent event that the other protocols do not have. */
@Serializable
data class PendingCliAgentUsageHistory(
    val client: String,
    val clientName: String? = null,
    val errorCode: String? = null,
    val command: String? = null,
    val path: String? = null,
)
