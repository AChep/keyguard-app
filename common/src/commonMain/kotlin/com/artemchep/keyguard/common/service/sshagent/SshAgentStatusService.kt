package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.model.SshAgentStatus
import kotlinx.coroutines.flow.Flow

interface SshAgentStatusService {
    fun getStatus(): Flow<SshAgentStatus>

    fun set(status: SshAgentStatus)
}
