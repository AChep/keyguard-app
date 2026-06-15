package com.artemchep.keyguard.common.service.sshagent.impl

import com.artemchep.keyguard.common.model.SshAgentStatus
import com.artemchep.keyguard.common.service.sshagent.SshAgentStatusService
import com.artemchep.keyguard.common.usecase.GetSshAgent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

class SshAgentStatusServiceStatelessProxy(
    private val getSshAgent: GetSshAgent,
) : SshAgentStatusService {
    constructor(directDI: DirectDI) : this(
        getSshAgent = directDI.instance(),
    )

    override fun getStatus(): Flow<SshAgentStatus> = getSshAgent()
        .map { enabled ->
            if (enabled) {
                SshAgentStatus.Ready
            } else SshAgentStatus.Stopped
        }

    override fun set(status: SshAgentStatus) {
        // Do nothing
    }
}
