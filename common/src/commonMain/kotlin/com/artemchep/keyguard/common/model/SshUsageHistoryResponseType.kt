package com.artemchep.keyguard.common.model

enum class SshUsageHistoryResponseType(
    val code: Long,
) {
    SUCCESS(0L),
    USER_DENIED(1L),
    KEY_NOT_FOUND(2L),
    FAILURE(3L),

    /**
     * An unrecognized response type, e.g. a code written by a newer app version.
     */
    UNKNOWN(-1L),
    ;

    companion object {
        fun of(code: Long): SshUsageHistoryResponseType =
            entries.firstOrNull { it.code == code }
            // Fall back to UNKNOWN instead of crashing so that a database
            // written by a newer app version stays readable.
                ?: UNKNOWN
    }
}
