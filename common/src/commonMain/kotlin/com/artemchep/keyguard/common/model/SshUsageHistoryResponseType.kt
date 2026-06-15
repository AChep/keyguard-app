package com.artemchep.keyguard.common.model

enum class SshUsageHistoryResponseType(
    val code: Long,
) {
    SUCCESS(0L),
    USER_DENIED(1L),
    KEY_NOT_FOUND(2L),
    FAILURE(3L),
    ;

    companion object {
        fun of(code: Long): SshUsageHistoryResponseType =
            entries.firstOrNull { it.code == code }
                ?: error("Unknown SSH usage history response type: $code")
    }
}
