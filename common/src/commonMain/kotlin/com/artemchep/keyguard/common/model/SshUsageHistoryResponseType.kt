package com.artemchep.keyguard.common.model

enum class SshUsageHistoryResponseType(
    val code: Long,
) {
    SUCCESS(code = 0L),
    USER_DENIED(code = 1L),
    KEY_NOT_FOUND(code = 2L),
    FAILURE(code = 3L),
    VAULT_LOCKED(code = 4L),

    /**
     * An unrecognized response type, e.g. a code written by a newer app version.
     */
    UNKNOWN(code = -1L),
    ;

    companion object {
        fun of(code: Long): SshUsageHistoryResponseType =
            entries.firstOrNull { it.code == code }
            // Fall back to UNKNOWN instead of crashing so that a database
            // written by a newer app version stays readable.
                ?: UNKNOWN
    }
}
