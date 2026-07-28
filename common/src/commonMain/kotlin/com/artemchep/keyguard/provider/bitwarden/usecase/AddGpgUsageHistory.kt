package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.android.downloader.journal.GpgUsageHistoryRepository
import com.artemchep.keyguard.common.model.AddGpgUsageHistoryRequest
import com.artemchep.keyguard.common.model.DGpgUsageHistory
import com.artemchep.keyguard.common.usecase.AddGpgUsageHistory
import org.kodein.di.DirectDI
import org.kodein.di.instance

class AddGpgUsageHistoryImpl(
    private val gpgUsageHistoryRepository: GpgUsageHistoryRepository,
) : AddGpgUsageHistory {
    constructor(directDI: DirectDI) : this(
        gpgUsageHistoryRepository = directDI.instance(),
    )

    override fun invoke(request: AddGpgUsageHistoryRequest) = kotlin.run {
        val model = DGpgUsageHistory(
            cipherId = request.cipherId,
            sessionId = request.sessionId,
            caller = request.caller,
            request = request.request,
            response = request.response,
            fingerprint = request.fingerprint,
            keygrip = request.keygrip,
            instant = request.instant,
        )
        gpgUsageHistoryRepository.put(model)
    }
}
