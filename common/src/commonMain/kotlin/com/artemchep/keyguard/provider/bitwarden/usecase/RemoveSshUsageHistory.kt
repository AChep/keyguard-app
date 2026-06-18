package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.android.downloader.journal.SshUsageHistoryRepository
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.usecase.RemoveSshUsageHistory
import org.kodein.di.DirectDI
import org.kodein.di.instance

class RemoveSshUsageHistoryImpl(
    private val sshUsageHistoryRepository: SshUsageHistoryRepository,
) : RemoveSshUsageHistory {
    constructor(directDI: DirectDI) : this(
        sshUsageHistoryRepository = directDI.instance(),
    )

    override fun invoke(): IO<Unit> = sshUsageHistoryRepository.removeAll()
}
