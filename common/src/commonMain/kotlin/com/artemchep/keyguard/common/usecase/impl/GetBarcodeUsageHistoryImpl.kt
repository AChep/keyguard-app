package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.android.downloader.journal.BarcodeUsageHistoryRepository
import com.artemchep.keyguard.common.usecase.GetBarcodeUsageHistory
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetBarcodeUsageHistoryImpl(
    private val barcodeUsageHistoryRepository: BarcodeUsageHistoryRepository,
) : GetBarcodeUsageHistory {
    constructor(directDI: DirectDI) : this(
        barcodeUsageHistoryRepository = directDI.instance(),
    )

    override fun invoke(id: String) = barcodeUsageHistoryRepository.getById(id)
}
