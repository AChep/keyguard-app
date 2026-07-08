package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.model.AgentStatus
import kotlinx.coroutines.flow.Flow

interface AgentStatusService {
    fun getStatus(): Flow<AgentStatus>

    fun set(status: AgentStatus)
}
