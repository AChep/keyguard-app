package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.android.downloader.journal.GpgUsageHistoryRepository
import com.artemchep.keyguard.common.usecase.GetGpgUsageHistoryCount
import kotlinx.coroutines.flow.Flow
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetGpgUsageHistoryCountImpl(
    private val gpgUsageHistoryRepository: GpgUsageHistoryRepository,
) : GetGpgUsageHistoryCount {
    constructor(directDI: DirectDI) : this(
        gpgUsageHistoryRepository = directDI.instance(),
    )

    override fun invoke(): Flow<Long> = gpgUsageHistoryRepository.getCount()
}
