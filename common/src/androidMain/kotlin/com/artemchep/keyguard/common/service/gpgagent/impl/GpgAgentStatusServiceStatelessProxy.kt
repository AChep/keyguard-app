package com.artemchep.keyguard.common.service.gpgagent.impl

import com.artemchep.keyguard.common.model.AgentStatus
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentStatusService
import com.artemchep.keyguard.common.usecase.GetGpgAgent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GpgAgentStatusServiceStatelessProxy(
    private val getGpgAgent: GetGpgAgent,
) : GpgAgentStatusService {

    override fun getStatus(): Flow<AgentStatus> = getGpgAgent()
        .map { enabled ->
            if (enabled) {
                AgentStatus.Ready
            } else {
                AgentStatus.Stopped
            }
        }

    override fun set(status: AgentStatus) {
        // Do nothing.
    }
}
