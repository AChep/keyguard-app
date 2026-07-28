package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.android.downloader.journal.GpgUsageHistoryRepository
import com.artemchep.keyguard.common.model.DGpgUsageHistory
import com.artemchep.keyguard.common.model.GpgUsageHistoryMode
import com.artemchep.keyguard.common.usecase.GetGpgUsageHistory
import kotlinx.coroutines.flow.Flow
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetGpgUsageHistoryImpl(
    private val gpgUsageHistoryRepository: GpgUsageHistoryRepository,
) : GetGpgUsageHistory {
    constructor(directDI: DirectDI) : this(
        gpgUsageHistoryRepository = directDI.instance(),
    )

    override fun invoke(
        mode: GpgUsageHistoryMode,
    ): Flow<List<DGpgUsageHistory>> = when (mode) {
        is GpgUsageHistoryMode.Recent -> gpgUsageHistoryRepository.getRecent()
        is GpgUsageHistoryMode.Cipher -> gpgUsageHistoryRepository.getByCipherId(mode.cipherId)
    }
}
