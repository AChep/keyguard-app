package com.artemchep.keyguard.common.service.sshagent.impl

import com.artemchep.keyguard.common.model.AgentStatus
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

    override fun getStatus(): Flow<AgentStatus> = getSshAgent()
        .map { enabled ->
            if (enabled) {
                AgentStatus.Ready
            } else AgentStatus.Stopped
        }

    override fun set(status: AgentStatus) {
        // Do nothing
    }
}
