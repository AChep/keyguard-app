package com.artemchep.keyguard.common.service.browseragent.impl

import com.artemchep.keyguard.common.service.agent.AgentStatusService
import com.artemchep.keyguard.common.service.agent.impl.AgentStatusServiceImpl
import com.artemchep.keyguard.common.service.browseragent.BrowserAutofillAgentStatusService

class BrowserAutofillAgentStatusServiceImpl :
    BrowserAutofillAgentStatusService,
    AgentStatusService by AgentStatusServiceImpl()
