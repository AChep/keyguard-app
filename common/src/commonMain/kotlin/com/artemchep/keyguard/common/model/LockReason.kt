package com.artemchep.keyguard.common.model

enum class LockReason {
    TIMEOUT,

    /**
     * A user has clicked the lock button to manually
     * lock the vault.
     */
    LOCK,
}
