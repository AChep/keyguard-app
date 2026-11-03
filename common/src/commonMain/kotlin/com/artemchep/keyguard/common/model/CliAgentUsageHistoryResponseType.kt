package com.artemchep.keyguard.common.model

enum class CliAgentUsageHistoryResponseType(
    val code: Long,
) {
    SUCCESS(code = 0L),
    USER_DENIED(code = 1L),
    NOT_FOUND(code = 2L),
    VAULT_LOCKED(code = 3L),
    TIMED_OUT(code = 4L),

    /** The client left before the request was answered. */
    CANCELLED(code = 5L),
    FAILURE(code = 6L),

    /**
     * An unrecognized response type, e.g. a code written by a newer app version.
     */
    UNKNOWN(code = -1L),
    ;

    companion object {
        fun of(code: Long): CliAgentUsageHistoryResponseType =
            entries.firstOrNull { it.code == code }
            // Fall back to UNKNOWN instead of crashing so that a database
            // written by a newer app version stays readable.
                ?: UNKNOWN
    }
}
