package com.artemchep.keyguard.core.store.bitwarden

import arrow.optics.optics
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@optics
data class BitwardenMeta(
    /**
     * Id of the bitwarden account, generated on
     * login.
     */
    val accountId: String,
    // common
    val lastSyncTimestamp: Instant? = null,
    val lastSyncResult: LastSyncResult? = null,
) {
    companion object;

    @Serializable
    sealed interface LastSyncResult {
        @Serializable
        @SerialName("success")
        data object Success : LastSyncResult

        @Serializable
        @SerialName("failure")
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
