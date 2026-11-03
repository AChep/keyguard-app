package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.android.downloader.journal.CliAgentUsageHistoryRepository
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryMode
import com.artemchep.keyguard.common.model.DCliAgentUsageHistory
import com.artemchep.keyguard.common.usecase.GetCliAgentUsageHistory
import kotlinx.coroutines.flow.Flow

class GetCliAgentUsageHistoryImpl(
    private val cliAgentUsageHistoryRepository: CliAgentUsageHistoryRepository,
) : GetCliAgentUsageHistory {
    override fun invoke(
        mode: CliAgentUsageHistoryMode,
    ): Flow<List<DCliAgentUsageHistory>> = when (mode) {
        is CliAgentUsageHistoryMode.Recent -> cliAgentUsageHistoryRepository.getRecent(mode.client)
        is CliAgentUsageHistoryMode.Cipher -> cliAgentUsageHistoryRepository.getByCipherId(mode.cipherId)
    }
}
