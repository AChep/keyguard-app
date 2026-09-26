package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.android.downloader.journal.BarcodeUsageHistoryRepository
import com.artemchep.keyguard.common.usecase.GetBarcodeUsageHistory

class GetBarcodeUsageHistoryImpl(
    private val barcodeUsageHistoryRepository: BarcodeUsageHistoryRepository,
) : GetBarcodeUsageHistory {
    override fun invoke(id: String) = barcodeUsageHistoryRepository.getById(id)
}
