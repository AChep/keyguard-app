package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.android.downloader.journal.GpgUsageHistoryRepository
import com.artemchep.keyguard.common.usecase.GetGpgUsageHistoryCount
import kotlinx.coroutines.flow.Flow

class GetGpgUsageHistoryCountImpl(
    private val gpgUsageHistoryRepository: GpgUsageHistoryRepository,
) : GetGpgUsageHistoryCount {
    override fun invoke(): Flow<Long> = gpgUsageHistoryRepository.getCount()
}
