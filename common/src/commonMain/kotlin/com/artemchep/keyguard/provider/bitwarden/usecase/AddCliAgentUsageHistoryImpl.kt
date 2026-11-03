package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.android.downloader.journal.CliAgentUsageHistoryRepository
import com.artemchep.keyguard.common.model.AddCliAgentUsageHistoryRequest
import com.artemchep.keyguard.common.model.DCliAgentUsageHistory
import com.artemchep.keyguard.common.usecase.AddCliAgentUsageHistory

class AddCliAgentUsageHistoryImpl(
    private val cliAgentUsageHistoryRepository: CliAgentUsageHistoryRepository,
) : AddCliAgentUsageHistory {
    override fun invoke(request: AddCliAgentUsageHistoryRequest) = kotlin.run {
        val model = DCliAgentUsageHistory(
            cipherId = request.cipherId,
            sessionId = request.sessionId,
            client = request.client,
            caller = request.caller,
            clientName = request.clientName,
            request = request.request,
            response = request.response,
            errorCode = request.errorCode,
            command = request.command,
            path = request.path,
            instant = request.instant,
            eventId = request.eventId,
        )
        cliAgentUsageHistoryRepository.put(model)
    }
}
