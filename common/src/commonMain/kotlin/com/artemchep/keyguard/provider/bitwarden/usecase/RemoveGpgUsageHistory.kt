package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.android.downloader.journal.GpgUsageHistoryRepository
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.usecase.RemoveGpgUsageHistory
import org.kodein.di.DirectDI
import org.kodein.di.instance

class RemoveGpgUsageHistoryImpl(
    private val gpgUsageHistoryRepository: GpgUsageHistoryRepository,
) : RemoveGpgUsageHistory {
    constructor(directDI: DirectDI) : this(
        gpgUsageHistoryRepository = directDI.instance(),
    )

    override fun invoke(): IO<Unit> = gpgUsageHistoryRepository.removeAll()
}
