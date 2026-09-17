package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.android.downloader.journal.SshUsageHistoryRepository
import com.artemchep.keyguard.common.usecase.GetSshUsageHistoryCount
import kotlinx.coroutines.flow.Flow

class GetSshUsageHistoryCountImpl(
    private val sshUsageHistoryRepository: SshUsageHistoryRepository,
) : GetSshUsageHistoryCount {
    override fun invoke(): Flow<Long> = sshUsageHistoryRepository.getCount()
}
