package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.android.downloader.journal.BarcodeUsageHistoryRepository
import com.artemchep.keyguard.common.model.DBarcodeUsageHistory
import com.artemchep.keyguard.common.usecase.PutBarcodeUsageHistory
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Clock

class PutBarcodeUsageHistoryImpl(
    private val barcodeUsageHistoryRepository: BarcodeUsageHistoryRepository,
) : PutBarcodeUsageHistory {
    constructor(directDI: DirectDI) : this(
        barcodeUsageHistoryRepository = directDI.instance(),
    )

    override fun invoke(
        id: String,
        type: String,
    ) = barcodeUsageHistoryRepository.put(
        DBarcodeUsageHistory(
            id = id,
            type = type,
            createdAt = Clock.System.now(),
        ),
    )
}
