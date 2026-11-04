package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.android.downloader.journal.CliAgentUsageHistoryRepository
import com.artemchep.keyguard.android.downloader.journal.GpgUsageHistoryRepository
import com.artemchep.keyguard.android.downloader.journal.SshUsageHistoryRepository
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.usecase.RemoveAgentHistoryOlderThan
import kotlin.time.Instant

class RemoveAgentHistoryOlderThanImpl(
    private val sshUsageHistoryRepository: SshUsageHistoryRepository,
    private val gpgUsageHistoryRepository: GpgUsageHistoryRepository,
    private val cliAgentUsageHistoryRepository: CliAgentUsageHistoryRepository,
) : RemoveAgentHistoryOlderThan {
    override fun invoke(cutoff: Instant): IO<Unit> = ioEffect {
        sshUsageHistoryRepository.removeOlderThan(cutoff).bind()
        gpgUsageHistoryRepository.removeOlderThan(cutoff).bind()
        cliAgentUsageHistoryRepository.removeOlderThan(cutoff).bind()
    }
}
