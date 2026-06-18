package com.artemchep.keyguard.common.model

sealed interface SshUsageHistoryMode {
    data object Recent : SshUsageHistoryMode

    data class Cipher(
        val cipherId: String,
    ) : SshUsageHistoryMode
}
