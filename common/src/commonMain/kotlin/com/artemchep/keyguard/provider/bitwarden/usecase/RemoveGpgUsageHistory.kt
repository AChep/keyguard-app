package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.android.downloader.journal.GpgUsageHistoryRepository
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.usecase.RemoveGpgUsageHistory

class RemoveGpgUsageHistoryImpl(
    private val gpgUsageHistoryRepository: GpgUsageHistoryRepository,
) : RemoveGpgUsageHistory {
    override fun invoke(): IO<Unit> = gpgUsageHistoryRepository.removeAll()
}
