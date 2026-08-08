package com.artemchep.keyguard.common.service.pendinghistory

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Enqueues one usage-history event, stamping it with a fresh unique id
 * and the current time. The id doubles as the [eventId] the flusher
 * writes into the vault tables, keeping replayed flushes idempotent.
 */
@OptIn(ExperimentalUuidApi::class)
@Suppress("LongParameterList")
fun PendingUsageHistoryQueue.enqueueEvent(
    protocol: PendingUsageHistory.Protocol,
    sessionId: String,
    caller: String?,
    requestType: String,
    responseType: String,
    cipherId: String? = null,
    fingerprint: String? = null,
    keygrip: String? = null,
    coalescenceKey: String? = null,
): IO<Unit> = ioEffect {
    val item = PendingUsageHistory(
        id = Uuid.random().toString(),
        protocol = protocol,
        sessionId = sessionId,
        caller = caller,
        requestType = requestType,
        responseType = responseType,
        cipherId = cipherId,
        fingerprint = fingerprint,
        keygrip = keygrip,
        timestampEpochMilliseconds = Clock.System
            .now()
            .toEpochMilliseconds(),
        coalescenceKey = coalescenceKey,
    )
    enqueue(item)
        .bind()
}
