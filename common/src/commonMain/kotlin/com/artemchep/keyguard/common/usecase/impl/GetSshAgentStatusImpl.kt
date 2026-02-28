package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.sshagent.SshAgentStatusService
import com.artemchep.keyguard.common.usecase.GetSshAgentStatus
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetSshAgentStatusImpl(
    sshAgentStatusService: SshAgentStatusService,
) : GetSshAgentStatus {
    private val sharedFlow = sshAgentStatusService.getStatus()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        sshAgentStatusService = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
