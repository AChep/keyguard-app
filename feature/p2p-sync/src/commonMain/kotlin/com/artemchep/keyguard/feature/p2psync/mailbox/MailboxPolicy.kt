package com.artemchep.keyguard.feature.p2psync.mailbox

/** What a device posts to the relay, and when, see [MailboxCodec]. */
object MailboxPolicy {
    /** How long every request waits after a refusal that names no time. */
    const val DEFAULT_RETRY_AFTER_SECONDS: Long = 60L

    /** The longest pause a refusal causes, whatever the relay says: an hour. */
    const val MAX_RETRY_AFTER_SECONDS: Long = 60L * 60L

    private const val MILLIS_PER_SECOND = 1_000L

    private const val PERCENT = 100L

    /**
     * `true` if a state item with the [tag] should be posted: no live session with the peer
     * acknowledged the same state, [liveAcked], the relay does not hold it already,
     * [relayTag], and the relay did not refuse it as too large, [refusedTag]: it waits for
     * the content to change.
     */
    fun shouldPost(
        tag: String,
        relayTag: String?,
        refusedTag: String?,
        liveAcked: Boolean,
    ): Boolean = !liveAcked && tag != relayTag && tag != refusedTag

    /**
     * `true` if an item that expires at [expiresAtMs], if known, has less than
     * [aheadPercent] of its lifetime [ttlMs] left at [nowMs]: it is posted again, the same,
     * so it never expires while its sender keeps it.
     */
    fun isExpiring(
        expiresAtMs: Long?,
        nowMs: Long,
        ttlMs: Long,
        aheadPercent: Long,
    ): Boolean = expiresAtMs != null && expiresAtMs - nowMs <= ttlMs * aheadPercent / PERCENT

    /** How long to pause after a refusal with the `Retry-After` of [seconds], within bounds. */
    fun retryAfterMs(seconds: Long?): Long =
        (seconds ?: DEFAULT_RETRY_AFTER_SECONDS).coerceIn(1L, MAX_RETRY_AFTER_SECONDS) * MILLIS_PER_SECOND

    /** The lifetime of a posted item: [MailboxLimits.TTL_SECONDS], or less if the relay keeps items for less. */
    fun ttlSeconds(relayTtlSeconds: Long?): Long =
        relayTtlSeconds?.coerceIn(1L, MailboxLimits.TTL_SECONDS) ?: MailboxLimits.TTL_SECONDS

    /**
     * Returns the [missing] blobs with the blob [hash] too, last. The oldest ones go once
     * there are more than [MailboxLimits.MAX_MISSING].
     */
    fun withMissing(
        missing: List<String>,
        hash: String,
    ): List<String> = if (hash in missing) missing else (missing + hash).takeLast(MailboxLimits.MAX_MISSING)
}
