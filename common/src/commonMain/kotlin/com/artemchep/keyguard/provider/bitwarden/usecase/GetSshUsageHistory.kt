package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.android.downloader.journal.SshUsageHistoryRepository
import com.artemchep.keyguard.common.model.DSshUsageHistory
import com.artemchep.keyguard.common.model.SshUsageHistoryMode
import com.artemchep.keyguard.common.usecase.GetSshUsageHistory
import kotlinx.coroutines.flow.Flow
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetSshUsageHistoryImpl(
    private val sshUsageHistoryRepository: SshUsageHistoryRepository,
) : GetSshUsageHistory {
    constructor(directDI: DirectDI) : this(
        sshUsageHistoryRepository = directDI.instance(),
    )

    override fun invoke(
        mode: SshUsageHistoryMode,
    ): Flow<List<DSshUsageHistory>> = when (mode) {
        is SshUsageHistoryMode.Recent -> sshUsageHistoryRepository.getRecent()
        is SshUsageHistoryMode.Cipher -> sshUsageHistoryRepository.getByCipherId(mode.cipherId)
    }
}
