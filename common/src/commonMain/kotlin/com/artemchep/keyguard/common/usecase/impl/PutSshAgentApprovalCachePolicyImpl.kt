package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutSshAgentApprovalCachePolicy

class PutSshAgentApprovalCachePolicyImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutSshAgentApprovalCachePolicy {
    override fun invoke(policy: AgentApprovalCachePolicy) = settingsReadWriteRepository
        .setSshAgentApprovalCachePolicy(policy)
}
