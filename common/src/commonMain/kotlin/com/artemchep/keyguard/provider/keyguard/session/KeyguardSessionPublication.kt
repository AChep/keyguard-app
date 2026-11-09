package com.artemchep.keyguard.provider.keyguard.session

/** Keeps a live session leased while an operation changes its roster and publishes it. */
interface KeyguardSessionPublication {
    /**
     * Publishes to the sessions captured before the change, waiting at most [timeoutMs].
     * Success means the roster was written, not that a peer stored it durably.
     */
    suspend fun publish(timeoutMs: Long): Boolean

    /** Releases the lease, after publication or failure. */
    suspend fun close()
}
