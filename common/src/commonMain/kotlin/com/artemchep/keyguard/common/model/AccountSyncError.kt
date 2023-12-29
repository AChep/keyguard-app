package com.artemchep.keyguard.common.model

sealed interface AccountSyncError {
    data object Unauthorized : AccountSyncError
    data object Forbidden : AccountSyncError

    /**
     * Something wrong that should be fixable
     * by trying to sync again.
     */
    data class Unknown(
        val message: String? = null,
    ) : AccountSyncError
}
