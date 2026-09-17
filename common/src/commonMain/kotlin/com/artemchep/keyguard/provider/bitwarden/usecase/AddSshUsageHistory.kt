package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.android.downloader.journal.SshUsageHistoryRepository
import com.artemchep.keyguard.common.model.AddSshUsageHistoryRequest
import com.artemchep.keyguard.common.model.DSshUsageHistory
import com.artemchep.keyguard.common.usecase.AddSshUsageHistory

class AddSshUsageHistoryImpl(
    private val sshUsageHistoryRepository: SshUsageHistoryRepository,
) : AddSshUsageHistory {
    override fun invoke(request: AddSshUsageHistoryRequest) = kotlin.run {
        val model = DSshUsageHistory(
            cipherId = request.cipherId,
            sessionId = request.sessionId,
            caller = request.caller,
            request = request.request,
            response = request.response,
            fingerprint = request.fingerprint,
            instant = request.instant,
            eventId = request.eventId,
        )
        sshUsageHistoryRepository.put(model)
    }
}
