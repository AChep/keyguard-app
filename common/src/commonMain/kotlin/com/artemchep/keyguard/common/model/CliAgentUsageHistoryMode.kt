package com.artemchep.keyguard.common.model

sealed interface CliAgentUsageHistoryMode {
    data class Recent(
        val client: CliAgentUsageHistoryClient,
    ) : CliAgentUsageHistoryMode

    data class Cipher(
        val cipherId: String,
    ) : CliAgentUsageHistoryMode
}
