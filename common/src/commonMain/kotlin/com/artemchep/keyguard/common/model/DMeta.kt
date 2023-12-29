package com.artemchep.keyguard.common.model

import kotlinx.datetime.Instant

data class DMeta(
    val accountId: AccountId,
    val lastSyncTimestamp: Instant? = null,
    val lastSyncResult: LastSyncResult? = null,
) : HasAccountId {
    override fun accountId(): String = accountId.id

    sealed interface LastSyncResult {
        data object Success : LastSyncResult

        data class Failure(
            val timestamp: Instant,
            val reason: String? = null,
            /**
             * `true` if you should ask user to authenticate to use
             * this account, `false` otherwise.
             */
            val requiresAuthentication: Boolean = false,
        ) : LastSyncResult
    }
}
