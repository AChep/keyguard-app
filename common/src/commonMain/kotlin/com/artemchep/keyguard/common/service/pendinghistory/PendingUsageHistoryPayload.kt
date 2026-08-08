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
)
