package com.artemchep.keyguard.common.service.gpgagent.impl

import com.artemchep.keyguard.common.service.agent.AgentStatusService
import com.artemchep.keyguard.common.service.agent.impl.AgentStatusServiceImpl
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentStatusService

class GpgAgentStatusServiceImpl :
    GpgAgentStatusService,
    AgentStatusService by AgentStatusServiceImpl()
