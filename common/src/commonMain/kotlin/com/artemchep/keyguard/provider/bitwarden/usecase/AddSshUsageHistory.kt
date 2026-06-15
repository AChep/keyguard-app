package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.android.downloader.journal.SshUsageHistoryRepository
import com.artemchep.keyguard.common.model.AddSshUsageHistoryRequest
import com.artemchep.keyguard.common.model.DSshUsageHistory
import com.artemchep.keyguard.common.usecase.AddSshUsageHistory
import org.kodein.di.DirectDI
import org.kodein.di.instance

class AddSshUsageHistoryImpl(
    private val sshUsageHistoryRepository: SshUsageHistoryRepository,
) : AddSshUsageHistory {
    constructor(directDI: DirectDI) : this(
        sshUsageHistoryRepository = directDI.instance(),
    )

    override fun invoke(request: AddSshUsageHistoryRequest) = kotlin.run {
        val model = DSshUsageHistory(
            cipherId = request.cipherId,
            sessionId = request.sessionId,
            caller = request.caller,
            request = request.request,
            response = request.response,
            fingerprint = request.fingerprint,
            instant = request.instant,
        )
        sshUsageHistoryRepository.put(model)
    }
}
