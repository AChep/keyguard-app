package com.artemchep.keyguard.common.service.agent.impl

import com.artemchep.keyguard.common.model.AgentStatus
import com.artemchep.keyguard.common.service.agent.AgentStatusService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class AgentStatusServiceImpl : AgentStatusService {
    private val sink = MutableStateFlow<AgentStatus>(AgentStatus.Unsupported)

    override fun getStatus(): Flow<AgentStatus> = sink

    override fun set(status: AgentStatus) {
        sink.value = status
    }
}
