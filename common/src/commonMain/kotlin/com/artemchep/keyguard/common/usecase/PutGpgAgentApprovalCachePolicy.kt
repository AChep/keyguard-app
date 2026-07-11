package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy

interface PutGpgAgentApprovalCachePolicy : (AgentApprovalCachePolicy) -> IO<Unit>
