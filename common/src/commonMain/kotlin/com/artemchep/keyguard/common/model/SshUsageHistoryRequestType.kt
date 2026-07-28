package com.artemchep.keyguard.common.model

enum class SshUsageHistoryRequestType(
    val code: Long,
) {
    AGENT_LIST_KEYS(0L),
    AGENT_SIGN_DATA(1L),

    /**
     * An unrecognized request type, e.g. a code written by a newer app version.
     */
    UNKNOWN(-1L),
    ;

    companion object {
        fun of(code: Long): SshUsageHistoryRequestType =
            entries.firstOrNull { it.code == code }
            // Fall back to UNKNOWN instead of crashing so that a database
            // written by a newer app version stays readable.
                ?: UNKNOWN
    }
}
