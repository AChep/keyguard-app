package com.artemchep.keyguard.common.model

enum class GpgUsageHistoryResponseType(
    val code: Long,
) {
    SUCCESS(0L),
    USER_DENIED(1L),
    KEY_NOT_FOUND(2L),
    VAULT_LOCKED(3L),
    UNSUPPORTED(4L),
    FAILURE(5L),

    /**
     * An unrecognized response type, e.g. a code written by a newer app version.
     */
    UNKNOWN(-1L),
    ;

    companion object {
        fun of(code: Long): GpgUsageHistoryResponseType =
            entries.firstOrNull { it.code == code }
            // Fall back to UNKNOWN instead of crashing so that a database
            // written by a newer app version stays readable.
                ?: UNKNOWN
    }
}
