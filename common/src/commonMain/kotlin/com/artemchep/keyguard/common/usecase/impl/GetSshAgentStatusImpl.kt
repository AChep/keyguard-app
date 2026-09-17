package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.sshagent.SshAgentStatusService
import com.artemchep.keyguard.common.usecase.GetSshAgentStatus
import kotlinx.coroutines.flow.distinctUntilChanged

class GetSshAgentStatusImpl(
    sshAgentStatusService: SshAgentStatusService,
) : GetSshAgentStatus {
    private val sharedFlow = sshAgentStatusService.getStatus()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
