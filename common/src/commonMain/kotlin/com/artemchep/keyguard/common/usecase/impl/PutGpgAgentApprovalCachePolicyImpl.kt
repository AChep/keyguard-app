package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutGpgAgentApprovalCachePolicy

class PutGpgAgentApprovalCachePolicyImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutGpgAgentApprovalCachePolicy {
    override fun invoke(policy: AgentApprovalCachePolicy) = settingsReadWriteRepository
        .setGpgAgentApprovalCachePolicy(policy)
}
