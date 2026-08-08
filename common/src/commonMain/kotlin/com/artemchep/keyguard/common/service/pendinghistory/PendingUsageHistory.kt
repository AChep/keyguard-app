package com.artemchep.keyguard.common.service.pendinghistory

import com.artemchep.keyguard.common.io.IO

/**
 * One usage-history event captured while the vault was locked and the
 * real usage-history tables were unreachable. Queued in the exposed
 * database and drained into the vault database on unlock.
 */
data class PendingUsageHistory(
    val id: String,
    val protocol: Protocol,
    val sessionId: String,
    val caller: String?,
    val requestType: String,
    val responseType: String,
    val cipherId: String?,
    val fingerprint: String?,
    val keygrip: String?,
    val timestampEpochMilliseconds: Long,
    /**
     * Events sharing a non-null key overwrite each other in the queue
     * instead of piling up, so a burst of routine probes (an agent
     * client listing keys on every connection) can not evict the rare
     * security-relevant events past the queue cap. In-process only,
     * never persisted.
     */
    val coalescenceKey: String? = null,
) {
    enum class Protocol {
        OPENPGP,
        SSH,
    }
}

/**
 * A queued event as stored at rest: everything except the ordering key
 * is sealed to the vault-held envelope key, see [PendingUsageHistoryEnvelope].
 */
class SealedPendingUsageHistory(
    val id: String,
    val timestampEpochMilliseconds: Long,
    val payload: ByteArray,
)

interface PendingUsageHistoryQueue {
    fun get(): IO<List<SealedPendingUsageHistory>>

    /**
     * Seals and persists the event. When no envelope public key has been
     * provisioned yet, the event is dropped instead of being written in
     * plaintext at the exposed protection tier.
     */
    fun enqueue(item: PendingUsageHistory): IO<Unit>

    fun remove(id: String): IO<Unit>
}
