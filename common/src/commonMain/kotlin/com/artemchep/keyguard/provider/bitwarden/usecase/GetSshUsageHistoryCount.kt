package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.android.downloader.journal.SshUsageHistoryRepository
import com.artemchep.keyguard.common.usecase.GetSshUsageHistoryCount
import kotlinx.coroutines.flow.Flow
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetSshUsageHistoryCountImpl(
    private val sshUsageHistoryRepository: SshUsageHistoryRepository,
) : GetSshUsageHistoryCount {
    constructor(directDI: DirectDI) : this(
        sshUsageHistoryRepository = directDI.instance(),
    )

    override fun invoke(): Flow<Long> = sshUsageHistoryRepository.getCount()
}
