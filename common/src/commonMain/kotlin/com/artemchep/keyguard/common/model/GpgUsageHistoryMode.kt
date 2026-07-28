package com.artemchep.keyguard.common.model

sealed interface GpgUsageHistoryMode {
    data object Recent : GpgUsageHistoryMode

    data class Cipher(
        val cipherId: String,
    ) : GpgUsageHistoryMode
}
