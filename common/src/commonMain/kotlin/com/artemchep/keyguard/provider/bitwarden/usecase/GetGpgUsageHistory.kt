package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.android.downloader.journal.GpgUsageHistoryRepository
import com.artemchep.keyguard.common.model.DGpgUsageHistory
import com.artemchep.keyguard.common.model.GpgUsageHistoryMode
import com.artemchep.keyguard.common.usecase.GetGpgUsageHistory
import kotlinx.coroutines.flow.Flow

class GetGpgUsageHistoryImpl(
    private val gpgUsageHistoryRepository: GpgUsageHistoryRepository,
) : GetGpgUsageHistory {
    override fun invoke(
        mode: GpgUsageHistoryMode,
    ): Flow<List<DGpgUsageHistory>> = when (mode) {
        is GpgUsageHistoryMode.Recent -> gpgUsageHistoryRepository.getRecent()
        is GpgUsageHistoryMode.Cipher -> gpgUsageHistoryRepository.getByCipherId(mode.cipherId)
    }
}
