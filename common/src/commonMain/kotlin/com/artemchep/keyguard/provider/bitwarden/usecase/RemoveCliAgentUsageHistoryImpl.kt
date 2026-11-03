package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.android.downloader.journal.CliAgentUsageHistoryRepository
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryClient
import com.artemchep.keyguard.common.usecase.RemoveCliAgentUsageHistory

class RemoveCliAgentUsageHistoryImpl(
    private val cliAgentUsageHistoryRepository: CliAgentUsageHistoryRepository,
) : RemoveCliAgentUsageHistory {
    override fun invoke(
        client: CliAgentUsageHistoryClient,
    ): IO<Unit> = cliAgentUsageHistoryRepository.removeByClient(client)
}
