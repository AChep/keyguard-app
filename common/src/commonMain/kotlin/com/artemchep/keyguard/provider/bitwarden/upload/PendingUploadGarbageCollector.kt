package com.artemchep.keyguard.provider.bitwarden.upload

import com.artemchep.keyguard.common.io.IO

/**
 * Best-effort cleanup for staged uploads left behind by an interrupted
 * mutation, sync, or account removal.
 */
interface PendingUploadGarbageCollector {
    /**
     * Sweeps stale, unreferenced artifacts after a successful account sync.
     */
    operator fun invoke(
        accountId: String,
    ): IO<Unit>

    /**
     * Immediately removes all staged artifacts after an account is deleted.
     */
    fun purge(
        accountId: String,
    ): IO<Unit>
}
