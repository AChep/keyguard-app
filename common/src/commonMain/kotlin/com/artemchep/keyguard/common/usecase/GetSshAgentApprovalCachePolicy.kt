package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.service.agent.AgentApprovalCacheConfigProvider
import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface GetSshAgentApprovalCachePolicy : () -> Flow<AgentApprovalCachePolicy> {
    /** Versioned write-through state used by approval memory in production. */
    val approvalCacheConfig: AgentApprovalCacheConfigProvider<AgentApprovalCachePolicy>?
        get() = null
}

object GetSshAgentApprovalCachePolicyNoOp : GetSshAgentApprovalCachePolicy {
    override fun invoke() = flowOf(AgentApprovalCachePolicy.Default)
}
