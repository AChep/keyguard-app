package com.artemchep.keyguard.common.service.sshagent.impl

import com.artemchep.keyguard.common.service.agent.AgentStatusService
import com.artemchep.keyguard.common.service.agent.impl.AgentStatusServiceImpl
import com.artemchep.keyguard.common.service.sshagent.SshAgentStatusService

class SshAgentStatusServiceImpl :
    SshAgentStatusService,
    AgentStatusService by AgentStatusServiceImpl()
