package com.artemchep.keyguard.common.model

enum class SshUsageHistoryRequestType(
    val code: Long,
) {
    AGENT_LIST_KEYS(0L),
    AGENT_SIGN_DATA(1L),
    ;

    companion object {
        fun of(code: Long): SshUsageHistoryRequestType =
            entries.firstOrNull { it.code == code }
                ?: error("Unknown SSH usage history request type: $code")
    }
}
