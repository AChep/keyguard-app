package com.artemchep.keyguard.common.service.sshagent.impl

import com.artemchep.keyguard.common.model.SshAgentStatus
import com.artemchep.keyguard.common.service.sshagent.SshAgentStatusService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class SshAgentStatusServiceImpl : SshAgentStatusService {
    private val sink = MutableStateFlow<SshAgentStatus>(SshAgentStatus.Unsupported)

    override fun getStatus(): Flow<SshAgentStatus> = sink

    override fun set(status: SshAgentStatus) {
        sink.value = status
    }
}
