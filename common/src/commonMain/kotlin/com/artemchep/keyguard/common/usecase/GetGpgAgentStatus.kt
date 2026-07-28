package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.AgentStatus
import kotlinx.coroutines.flow.Flow

interface GetGpgAgentStatus : () -> Flow<AgentStatus>
