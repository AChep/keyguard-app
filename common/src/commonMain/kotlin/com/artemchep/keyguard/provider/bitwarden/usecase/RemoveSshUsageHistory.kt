package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.android.downloader.journal.SshUsageHistoryRepository
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.usecase.RemoveSshUsageHistory

class RemoveSshUsageHistoryImpl(
    private val sshUsageHistoryRepository: SshUsageHistoryRepository,
) : RemoveSshUsageHistory {
    override fun invoke(): IO<Unit> = sshUsageHistoryRepository.removeAll()
}
