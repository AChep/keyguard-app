package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.android.downloader.journal.CliAgentUsageHistoryRepository
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryClient
import com.artemchep.keyguard.common.usecase.GetCliAgentUsageHistoryCount
import kotlinx.coroutines.flow.Flow

class GetCliAgentUsageHistoryCountImpl(
    private val cliAgentUsageHistoryRepository: CliAgentUsageHistoryRepository,
) : GetCliAgentUsageHistoryCount {
    override fun invoke(
        client: CliAgentUsageHistoryClient,
    ): Flow<Long> = cliAgentUsageHistoryRepository.getCount(client)
}
